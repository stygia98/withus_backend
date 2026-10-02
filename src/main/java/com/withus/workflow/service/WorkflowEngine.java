package com.withus.workflow.service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.withus.campaign.domain.CustomerPlaceholderSource;
import com.withus.campaign.domain.SendStatus;
import com.withus.campaign.mapper.SendLogMapper;
import com.withus.campaign.service.SendQueueService;
import com.withus.common.domain.Channel;
import com.withus.tracking.service.TrackEventRepository;
import com.withus.workflow.domain.NodeType;
import com.withus.workflow.domain.WorkflowInstance;
import com.withus.workflow.domain.WorkflowStep;
import com.withus.workflow.mapper.WorkflowInstanceMapper;
import com.withus.workflow.mapper.WorkflowStepMapper;

import tools.jackson.databind.ObjectMapper;

/**
 * 워크플로우 노드 실행기 (workflow-plan.md 2.2·3·5장, 엔진 2/4~4/4). 선점된 인스턴스 1건을 WAIT·END를
 * 만날 때까지 한 트랜잭션(tx2) 안에서 연속 실행한다. 오류가 나면 tx2 는 롤백되고(이미 적재한 SEND 포함,
 * uq_send_log_step 유니크가 재시도 때 중복을 막아줘 안전하다), 별도의 짧은 트랜잭션(tx3)으로
 * retry_count·next_run_at·FAILED 를 기록한다 — SendQueueService 와 같은 이유로 @Transactional
 * 대신 TransactionTemplate 을 쓴다(tx3 이 tx2 롤백에 영향받지 않아야 하므로).
 */
@Service
public class WorkflowEngine {

	private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

	/** retry_count(재시도 후 값)가 이 값을 넘으면 재시도 대신 FAILED(워크플로우 Plan 5장) */
	private static final int MAX_RETRY = 3;
	private static final int RETRY_DELAY_MINUTES = 5;

	private final WorkflowStepMapper workflowStepMapper;
	private final WorkflowInstanceMapper workflowInstanceMapper;
	private final SendQueueService sendQueueService;
	private final SendLogMapper sendLogMapper;
	private final TrackEventRepository trackEventRepository;
	private final ObjectMapper objectMapper;
	private final TransactionTemplate transactionTemplate;

	public WorkflowEngine(WorkflowStepMapper workflowStepMapper, WorkflowInstanceMapper workflowInstanceMapper,
			SendQueueService sendQueueService, SendLogMapper sendLogMapper,
			TrackEventRepository trackEventRepository, ObjectMapper objectMapper,
			PlatformTransactionManager transactionManager) {
		this.workflowStepMapper = workflowStepMapper;
		this.workflowInstanceMapper = workflowInstanceMapper;
		this.sendQueueService = sendQueueService;
		this.sendLogMapper = sendLogMapper;
		this.trackEventRepository = trackEventRepository;
		this.objectMapper = objectMapper;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	/** tx2 가 실패하면 여기서 잡아 tx3(retryOrFail)으로 기록한다 — 호출하는 쪽에는 예외를 던지지 않는다 */
	public void processOne(WorkflowInstance instance) {
		try {
			transactionTemplate.executeWithoutResult(status -> runNodes(instance));
		} catch (Exception e) {
			log.warn("워크플로우 인스턴스 실행 실패 instanceId={}", instance.getInstanceId(), e);
			retryOrFail(instance, e);
		}
	}

	private void runNodes(WorkflowInstance instance) {
		long currentStepId = instance.getCurrentStepId();
		SendStatus lastSendStatus = null;
		while (true) {
			WorkflowStep step = workflowStepMapper.findById(currentStepId);
			switch (step.getNodeType()) {
				case SEND_EMAIL, SEND_SMS -> {
					lastSendStatus = executeSend(instance, step);
					currentStepId = step.getNextStepId();
				}
				case WAIT -> {
					executeWait(instance, step, lastSendStatus);
					return;
				}
				case END -> {
					warnIfNotRunning(workflowInstanceMapper.complete(instance.getInstanceId()), instance, "complete");
					return;
				}
				case CONDITION -> {
					// "직전 SEND 의 결과" 는 CONDITION 을 지나면 의미가 없다 — 남겨 두면 뒤따르는 WAIT 가 PENDING 으로 보고
					// next_run_at 을 비운 채 영원히 기다린다(wake 는 SEND 바로 뒤 WAIT 만 깨운다, PR #35 리뷰 🔴2, Plan 3.1 "그 외")
					lastSendStatus = null;
					currentStepId = evaluateCondition(instance, step) ? step.getYesStepId() : step.getNoStepId();
				}
				case TRIGGER -> throw new IllegalStateException(
					"TRIGGER 는 인스턴스 생성 시 건너뛰므로 실행 중에는 도달할 수 없다");
			}
		}
	}

	/** 기록 UPDATE 가 0행이면 그 사이 인스턴스가 RUNNING 이 아니게 됐다(종료·삭제로 CANCELLED, 복구 후 다른 워커) — 덮어쓰지 않고 알린다 */
	private void warnIfNotRunning(int updatedRows, WorkflowInstance instance, String action) {
		if (updatedRows == 0) {
			log.warn("워크플로우 인스턴스가 RUNNING 이 아니어서 {} 를 반영하지 않았다 instanceId={}", action, instance.getInstanceId());
		}
	}

	/** tx3(workflow-plan.md 5장) — tx2 와 별도의 트랜잭션으로 재시도 또는 FAILED 를 기록한다 */
	private void retryOrFail(WorkflowInstance instance, Exception e) {
		int nextRetryCount = instance.getRetryCount() + 1;
		String errorMessage = e.getMessage();
		// PL 결정(PR #35 리뷰 🟡4): PRD 6.5-5 "3회 실패하면 FAILED" — 3번째 실패에서 FAILED (발송 큐의 "3회를 넘기면" 과 다른 규칙)
		if (nextRetryCount >= MAX_RETRY) {
			transactionTemplate.executeWithoutResult(status -> warnIfNotRunning(
				workflowInstanceMapper.recordFailed(instance.getInstanceId(), errorMessage), instance, "recordFailed"));
			return;
		}
		OffsetDateTime nextRunAt = OffsetDateTime.now(ZoneId.of("Asia/Seoul")).plusMinutes(RETRY_DELAY_MINUTES);
		transactionTemplate.executeWithoutResult(status -> warnIfNotRunning(
			workflowInstanceMapper.recordRetry(instance.getInstanceId(), nextRunAt, errorMessage), instance, "recordRetry"));
	}

	/**
	 * enqueueWorkflowStep 의 삽입 건수는 보지 않는다 — uq_send_log_step 유니크 제약이 재실행 시 중복
	 * 적재를 막아주므로 반환값과 무관하게 항상 다음 노드로 진행해야 멱등하다(workflow-plan.md 5장).
	 */
	private SendStatus executeSend(WorkflowInstance instance, WorkflowStep step) {
		Channel channel = step.getNodeType() == NodeType.SEND_EMAIL ? Channel.EMAIL : Channel.SMS;
		sendQueueService.enqueueWorkflowStep(instance.getCampaignId(), instance.getInstanceId(), step.getStepId(),
			instance.getCustomerId(), channel);
		return sendLogMapper.findStatusByInstanceStep(instance.getInstanceId(), step.getStepId());
	}

	/** workflow-plan.md 3.1 — PENDING 일 때만 next_run_at 을 비워 wake() 를 기다리고, 그 외는 즉시 계산한다 */
	private void executeWait(WorkflowInstance instance, WorkflowStep step, SendStatus lastSendStatus) {
		long nextStepId = step.getNextStepId();
		if (lastSendStatus == SendStatus.PENDING) {
			warnIfNotRunning(workflowInstanceMapper.moveToWaitPending(instance.getInstanceId(), nextStepId), instance,
				"moveToWaitPending");
		} else {
			OffsetDateTime nextRunAt = OffsetDateTime.now(ZoneId.of("Asia/Seoul")).plus(WaitDurations.of(step.getConfigJson(), objectMapper));
			warnIfNotRunning(workflowInstanceMapper.moveToWait(instance.getInstanceId(), nextStepId, nextRunAt), instance,
				"moveToWait");
		}
	}

	/**
	 * workflow-plan.md 3.2 — EMAIL_OPENED/CLICKED 는 이 인스턴스의 직전 메일(채널 지정, PL 리뷰 R3)에
	 * 봇이 아닌 이벤트가 있는지, PURCHASE_GTE 는 누적구매액을 비교한다. SKIPPED·FAILED 로 끝난 메일은
	 * track_event 가 없으므로 existsHumanEvent 가 자연히 false 를 돌려준다(별도 분기 불필요).
	 */
	private boolean evaluateCondition(WorkflowInstance instance, WorkflowStep step) {
		Map<String, Object> config = parseConfig(step);
		String condition = (String) config.get("condition");
		return switch (condition) {
			case "EMAIL_OPENED" -> evaluateEmailEvent(instance, "OPEN");
			case "EMAIL_CLICKED" -> evaluateEmailEvent(instance, "CLICK");
			case "PURCHASE_GTE" -> evaluatePurchase(instance, config);
			default -> throw new IllegalStateException("알 수 없는 조건: " + condition);
		};
	}

	private boolean evaluateEmailEvent(WorkflowInstance instance, String eventType) {
		Long sendLogId = sendLogMapper.findLatestSendLogId(instance.getInstanceId(), Channel.EMAIL);
		return sendLogId != null && trackEventRepository.existsHumanEvent(sendLogId, eventType);
	}

	private boolean evaluatePurchase(WorkflowInstance instance, Map<String, Object> config) {
		long amount = ((Number) config.get("amount")).longValue();
		CustomerPlaceholderSource source = sendLogMapper.findPlaceholderSource(instance.getCustomerId());
		Long totalPurchase = source.getTotalPurchase();
		return totalPurchase != null && totalPurchase >= amount;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> parseConfig(WorkflowStep step) {
		return objectMapper.readValue(step.getConfigJson(), Map.class);
	}
}
