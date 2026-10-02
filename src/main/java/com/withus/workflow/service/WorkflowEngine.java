package com.withus.workflow.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.withus.campaign.domain.SendStatus;
import com.withus.campaign.mapper.SendLogMapper;
import com.withus.campaign.service.SendQueueService;
import com.withus.common.domain.Channel;
import com.withus.workflow.domain.NodeType;
import com.withus.workflow.domain.WorkflowInstance;
import com.withus.workflow.domain.WorkflowStep;
import com.withus.workflow.mapper.WorkflowInstanceMapper;
import com.withus.workflow.mapper.WorkflowStepMapper;

import tools.jackson.databind.ObjectMapper;

/**
 * 워크플로우 노드 실행기 (workflow-plan.md 2.2·3장, 엔진 2/4). 선점된 인스턴스 1건을 WAIT·END를
 * 만날 때까지 한 트랜잭션(tx2) 안에서 연속 실행한다. CONDITION은 엔진 3/4에서 이 switch에 더한다.
 */
@Service
public class WorkflowEngine {

	private final WorkflowStepMapper workflowStepMapper;
	private final WorkflowInstanceMapper workflowInstanceMapper;
	private final SendQueueService sendQueueService;
	private final SendLogMapper sendLogMapper;
	private final ObjectMapper objectMapper;

	public WorkflowEngine(WorkflowStepMapper workflowStepMapper, WorkflowInstanceMapper workflowInstanceMapper,
			SendQueueService sendQueueService, SendLogMapper sendLogMapper, ObjectMapper objectMapper) {
		this.workflowStepMapper = workflowStepMapper;
		this.workflowInstanceMapper = workflowInstanceMapper;
		this.sendQueueService = sendQueueService;
		this.sendLogMapper = sendLogMapper;
		this.objectMapper = objectMapper;
	}

	/**
	 * tx2(workflow-plan.md 2.2) — SEND 적재를 포함해 전부 DB 안에서만 일어나므로 트랜잭션 하나로 묶을 수 있다.
	 * 오류 재시도(tx3, retry_count)는 엔진 4/4 범위라 여기서는 예외를 그대로 던진다 — 호출하는 쪽
	 * (WorkflowScheduler)이 건별로 잡아 배치 전체가 멈추지 않게 하고, 롤백된 인스턴스는 RUNNING에 남아
	 * 멈춤 복구(엔진 4/4)가 나중에 WAITING 으로 되돌린다.
	 */
	@Transactional
	public void processOne(WorkflowInstance instance) {
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
					workflowInstanceMapper.complete(instance.getInstanceId());
					return;
				}
				case CONDITION -> throw new UnsupportedOperationException("CONDITION 노드 실행은 엔진 3/4에서 구현한다");
				case TRIGGER -> throw new IllegalStateException(
					"TRIGGER 는 인스턴스 생성 시 건너뛰므로 실행 중에는 도달할 수 없다");
			}
		}
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
			workflowInstanceMapper.moveToWaitPending(instance.getInstanceId(), nextStepId);
		} else {
			OffsetDateTime nextRunAt = OffsetDateTime.now(ZoneId.of("Asia/Seoul")).plus(waitDuration(step));
			workflowInstanceMapper.moveToWait(instance.getInstanceId(), nextStepId, nextRunAt);
		}
	}

	@SuppressWarnings("unchecked")
	private Duration waitDuration(WorkflowStep step) {
		Map<String, Object> config = objectMapper.readValue(step.getConfigJson(), Map.class);
		long amount = ((Number) config.get("amount")).longValue();
		String unit = (String) config.get("unit");
		return switch (unit) {
			case "MINUTE" -> Duration.ofMinutes(amount);
			case "HOUR" -> Duration.ofHours(amount);
			case "DAY" -> Duration.ofDays(amount);
			default -> throw new IllegalStateException("알 수 없는 WAIT 단위: " + unit);
		};
	}
}
