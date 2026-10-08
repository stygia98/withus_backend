package com.withus.campaign.service;

import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.withus.campaign.domain.SendKind;
import com.withus.campaign.domain.SendLog;
import com.withus.campaign.domain.Template;
import com.withus.campaign.mapper.SendLogMapper;
import com.withus.campaign.mapper.TemplateMapper;
import com.withus.campaign.service.messaging.ErrorType;
import com.withus.campaign.service.messaging.MessageSenderRouter;
import com.withus.campaign.service.messaging.OutboundMessage;
import com.withus.campaign.service.messaging.SendResult;
import com.withus.common.token.UnsubscribeTokens;
import com.withus.workflow.service.WorkflowWakeup;
import com.withus.customer.service.ConsentService;

/**
 * 발송 큐 디스패처 (발송 큐 Plan 3장) — 선점(tx1, 짧은 UPDATE 한 건으로 자동 커밋)
 * → 트랜잭션 밖에서 TokenBucket 통과·렌더링(MessageComposer)·MessageSender 호출 → 결과 기록(tx2, 역시 단건 UPDATE).
 */
@Component
public class SendDispatcher {

	private static final Logger log = LoggerFactory.getLogger(SendDispatcher.class);

	/** 한 번 실행(dispatch 호출)에 처리할 건수 상한 — 스케줄러 풀(5)을 오래 점유하지 않게 한다 */
	private static final int MAX_PER_RUN = 200;

	/** attempt_count(재시도 후 값) 1·2·3 회차의 재시도 간격. 4회째는 재시도 없이 FAILED(발송 큐 Plan 8장) */
	private static final Duration[] RETRY_INTERVALS = { Duration.ofMinutes(1), Duration.ofMinutes(5),
		Duration.ofMinutes(15) };

	private final SendLogMapper sendLogMapper;
	private final TemplateMapper templateMapper;
	private final MessageSenderRouter messageSenderRouter;
	private final ConsentService consentService;
	private final MessageComposer messageComposer;
	private final UnsubscribeTokens unsubscribeTokens;
	private final TokenBucket tokenBucket;
	private final SendWindow sendWindow;
	private final String trackingBaseUrl;
	private final WorkflowWakeup workflowWakeup;
	private final boolean schedulerEnabled;

	/**
	 * 정상 종료가 시작됐다는 신호(이슈 #92). 선점 묶음(최대 50건)을 초당 1건으로 보내면 50초가 걸려 Spring 종료 단계 제한(30초)을
	 * 넘기고, 그 뒤 DB 풀이 먼저 닫혀 인터럽트 경로의 revertUnprocessed 가 실패한다(선점분이 SENDING 으로 남아 UNKNOWN_RESULT 로 누락).
	 * ContextClosedEvent 는 Lifecycle 정지·빈 소멸(DB 풀 종료)보다 먼저 발행되므로, 이 신호를 보면 현재 건만 마치고
	 * 남은 선점분을 DB 가 살아 있을 때 PENDING 으로 되돌린다
	 */
	private volatile boolean stopping;

	public SendDispatcher(SendLogMapper sendLogMapper, TemplateMapper templateMapper,
			MessageSenderRouter messageSenderRouter, ConsentService consentService, MessageComposer messageComposer,
			UnsubscribeTokens unsubscribeTokens,
			WorkflowWakeup workflowWakeup,
			@Value("${ses.max-send-rate}") int maxSendRate,
			@Value("${withus.send-window.start}") String sendWindowStart,
			@Value("${withus.send-window.end}") String sendWindowEnd,
			@Value("${withus.tracking.base-url}") String trackingBaseUrl,
			@Value("${withus.scheduler.send-dispatcher.enabled:true}") boolean schedulerEnabled) {
		this.sendLogMapper = sendLogMapper;
		this.templateMapper = templateMapper;
		this.messageSenderRouter = messageSenderRouter;
		this.consentService = consentService;
		this.messageComposer = messageComposer;
		this.unsubscribeTokens = unsubscribeTokens;
		this.workflowWakeup = workflowWakeup;
		this.tokenBucket = new TokenBucket(maxSendRate);
		this.sendWindow = new SendWindow(LocalTime.parse(sendWindowStart), LocalTime.parse(sendWindowEnd));
		this.trackingBaseUrl = trackingBaseUrl;
		this.schedulerEnabled = schedulerEnabled;
	}

	/**
	 * 테스트에서 dispatch() 를 직접 호출해 결정론적으로 검증할 때, 백그라운드 스케줄러가 같은 DB 를
	 * 동시에 건드리지 않도록 withus.scheduler.send-dispatcher.enabled=false 로 끌 수 있게 했다
	 */
	@Scheduled(fixedDelay = 1000)
	void scheduledDispatch() {
		if (schedulerEnabled && !stopping) {
			dispatch();
		}
	}

	@EventListener(ContextClosedEvent.class)
	void onContextClosed() {
		stopping = true;
	}

	public void dispatch() {
		int processed = 0;
		while (processed < MAX_PER_RUN && !stopping) {
			List<SendLog> claimed = sendLogMapper.claimBatch();
			if (claimed.isEmpty()) {
				return;
			}
			for (int i = 0; i < claimed.size(); i++) {
				SendLog sendLog = claimed.get(i);
				if (stopping) {
					// 정상 종료 중 — 아직 시작하지 않은 선점분을 DB 풀이 닫히기 전에 되돌린다(이슈 #92). attempt_count 는 소모하지 않는다
					revertUnprocessed(claimed.subList(i, claimed.size()));
					return;
				}
				try {
					processOne(sendLog);
				} catch (Exception e) {
					if (Thread.currentThread().isInterrupted()) {
						// 종료 중 인터럽트(TokenBucket 대기) — 남은 건이 발송 없이 attempt_count 만 소모하지 않도록
						// 루프를 빠져나가고, 아직 끝내지 못한 건(현재 건 포함)을 PENDING 으로 되돌린다(PR #21 리뷰)
						revertUnprocessed(claimed.subList(i, claimed.size()));
						return;
					}
					// 템플릿 누락 등 MessageSender.send 이전 예외 — 이 건만 재시도로 돌리고 배치는 계속 진행한다(PR #21 리뷰)
					safeRetryOrFail(sendLog, e.getMessage());
				}
				processed++;
			}
		}
	}

	/**
	 * 재시도 기록(DB)까지 실패해도 예외가 dispatch() 밖으로 나가면 이미 선점한 나머지 건이 미발송 상태로
	 * 10분 뒤 전부 FAILED 가 된다 — 이 건만 SENDING 으로 남겨 멈춤 복구에 맡기고 배치는 계속한다
	 */
	private void safeRetryOrFail(SendLog sendLog, String errorMessage) {
		try {
			retryOrFail(sendLog, errorMessage);
		} catch (Exception e) {
			log.error("재시도 기록 실패, SENDING 으로 남김 sendLogId={}", sendLog.getSendLogId(), e);
		}
	}

	private void revertUnprocessed(List<SendLog> rest) {
		for (SendLog sendLog : rest) {
			try {
				sendLogMapper.revertToPending(sendLog.getSendLogId());
			} catch (Exception e) {
				log.error("인터럽트 후 PENDING 복귀 실패, 멈춤 복구에 맡김 sendLogId={}", sendLog.getSendLogId(), e);
			}
		}
	}

	/**
	 * 종단 결과(SENT·FAILED·SKIPPED)를 먼저 기록(커밋)하고, 그 다음에 워크플로우 인스턴스를 깨운다(워크플로우 Plan 4.2).
	 * 한 트랜잭션으로 묶으면 wake 가 예외를 던질 때 이미 나간 메일의 SENT 기록까지 롤백되어 10분 뒤 UNKNOWN_RESULT 가 된다
	 * (PR #35 리뷰 🟡3). 깨우기가 실패해도 기록은 남고, WorkflowRecoveryJob 이 "next_run_at 이 빈 채 직전 SEND 가 끝난"
	 * 인스턴스를 주기적으로 찾아 깨운다.
	 */
	private void recordAndWake(SendLog sendLog, String action, java.util.function.IntSupplier record) {
		int updatedRows = record.getAsInt();
		warnIfNotRecorded(updatedRows, sendLog, action);
		if (updatedRows > 0) {
			wakeSafely(sendLog);
		}
	}

	private void wakeSafely(SendLog sendLog) {
		try {
			workflowWakeup.wake(sendLog);
		} catch (Exception e) {
			log.error("워크플로우 깨우기 실패 — 복구 작업이 다시 시도한다 sendLogId={}", sendLog.getSendLogId(), e);
		}
	}

	/** 결과 기록 UPDATE 가 status = 'SENDING' 조건 때문에 0행이면(멈춤 복구가 먼저 처리한 건) 경고만 남긴다 */

	private void warnIfNotRecorded(int updatedRows, SendLog sendLog, String action) {
		if (updatedRows == 0) {
			log.warn("{} 건너뜀: 이미 SENDING 이 아님(멈춤 복구가 먼저 처리했을 수 있음) sendLogId={}", action,
				sendLog.getSendLogId());
		}
	}

	/** 선점된 한 건을 트랜잭션 밖에서 처리하고 결과를 기록한다 */
	public void processOne(SendLog sendLog) {
		// NOTICE(F-12 수신동의 확인 안내)는 campaign_id 가 없어 템플릿이 없다 — MessageComposer 가 고정 문구로 렌더링한다(이슈 #81)
		Template template = sendLog.getKind() == SendKind.NOTICE ? null : resolveTemplate(sendLog);
		if (!recheck(sendLog, template)) {
			return; // recheck 안에서 SKIPPED·PENDING 복귀·시간창 보류를 이미 기록했다
		}
		tokenBucket.acquire();
		String unsubscribeToken = unsubscribeToken(sendLog);
		Optional<OutboundMessage> message = messageComposer.compose(sendLog, template,
			trackingBaseUrl + "/unsubscribe/" + unsubscribeToken,
			trackingBaseUrl + "/api/v1/unsubscribe/one-click/" + unsubscribeToken);
		if (message.isEmpty()) {
			wakeSafely(sendLog);
			return; // 쿠폰 유효기간 밖 — compose 안에서 이미 SKIPPED(COUPON_INVALID) 기록
		}
		SendResult result = messageSenderRouter.send(message.get());
		if (result.success()) {
			recordSentSafely(sendLog, result);
		} else if (result.errorType() == ErrorType.TRANSIENT) {
			retryOrFail(sendLog, result.errorMessage());
		} else {
			recordAndWake(sendLog, "recordFailed", () -> sendLogMapper.recordFailed(sendLog.getSendLogId(), result.errorMessage()));
		}
	}

	/**
	 * 발송은 이미 나갔다 — 성공 기록이 DB 오류로 실패해도 예외를 밖으로 던지면 dispatch() 가 재시도로 돌려
	 * 같은 메일이 한 번 더 나간다. 로그만 남기고 SENDING 으로 두어 멈춤 복구가 UNKNOWN_RESULT 로 처리하게 한다
	 * (CLAUDE.md 6장 5번 "중복 발송보다 누락이 낫다", PR #21 리뷰)
	 */
	private void recordSentSafely(SendLog sendLog, SendResult result) {
		try {
			recordAndWake(sendLog, "recordSent", () -> sendLogMapper.recordSent(sendLog.getSendLogId(), result.providerMessageId()));
		} catch (Exception e) {
			log.error("발송 성공 기록 실패 — 중복 발송을 피하려 재시도하지 않는다 sendLogId={}", sendLog.getSendLogId(), e);
		}
	}

	/** 일시 오류(TRANSIENT) 또는 MessageSender.send 이전 예외: 1·5·15분 뒤 재시도, 3회 넘으면 FAILED(발송 큐 Plan 8장) */
	private void retryOrFail(SendLog sendLog, String errorMessage) {
		int nextAttemptCount = sendLog.getAttemptCount() + 1;
		if (nextAttemptCount > RETRY_INTERVALS.length) {
			recordAndWake(sendLog, "recordFailed", () -> sendLogMapper.recordFailed(sendLog.getSendLogId(), errorMessage));
			return;
		}
		OffsetDateTime nextAttemptAt = OffsetDateTime.now(ZoneId.of("Asia/Seoul"))
			.plus(RETRY_INTERVALS[nextAttemptCount - 1]);
		warnIfNotRecorded(sendLogMapper.recordRetry(sendLog.getSendLogId(), nextAttemptAt, errorMessage), sendLog,
			"recordRetry");
	}

	/**
	 * 발송 직전 재확인(SendRecheck, 발송 큐 Plan 7장 1~6번). 통과하면 true.
	 * TEST(customer_id NULL)는 고객 관련 확인(1~3번)과 시간창(6번)을 모두 건너뛴다.
	 * 쿠폰 유효기간(7번)은 렌더링 작업에서 끼워 넣는다.
	 */
	private boolean recheck(SendLog sendLog, Template template) {
		if (sendLog.getKind() == SendKind.TEST) {
			return true;
		}
		if (!consentService.isSendable(sendLog.getCustomerId(), sendLog.getChannel())) {
			// isSendable 하나로 고객 삭제·수신동의 N·suppression 세 가지를 한꺼번에 본다(Plan 15장 B1)
			recordAndWake(sendLog, "recordSkipped", () -> sendLogMapper.recordSkipped(sendLog.getSendLogId(), "NOT_SENDABLE"));
			return false;
		}
		// NOTICE(F-12) 는 campaign_id 가 없다(PRD 7장) — 캠페인 상태 확인 대상이 아니다. primitive long 파라미터에
		// null 을 넘기면 언박싱 NPE 라 여기서 걸러낸다(PR #21 리뷰)
		if (sendLog.getCampaignId() != null) {
			String campaignStatus = sendLogMapper.findCampaignStatus(sendLog.getCampaignId());
			if ("COMPLETED".equals(campaignStatus)) {
				recordAndWake(sendLog, "recordSkipped",
					() -> sendLogMapper.recordSkipped(sendLog.getSendLogId(), "CAMPAIGN_COMPLETED"));
				return false;
			}
			if ("PAUSED".equals(campaignStatus)) {
				// 선점(claimBatch)은 PAUSED 를 걸러내지만, 선점 이후 바뀐 경우의 안전장치로 여기서도 본다(Plan 15장 A1)
				warnIfNotRecorded(sendLogMapper.revertToPending(sendLog.getSendLogId()), sendLog, "revertToPending");
				return false;
			}
		}
		boolean adOrNotice = sendLog.getKind() == SendKind.NOTICE || (template != null && template.isAd());
		if (adOrNotice) {
			Optional<OffsetDateTime> holdUntil = sendWindow.holdUntil();
			if (holdUntil.isPresent()) {
				warnIfNotRecorded(sendLogMapper.holdForSendWindow(sendLog.getSendLogId(), holdUntil.get()), sendLog,
					"holdForSendWindow");
				return false;
			}
		}
		return true;
	}

	private Template resolveTemplate(SendLog sendLog) {
		if (sendLog.getKind() == SendKind.TEST) {
			return templateMapper.findById(sendLog.getTemplateId());
		}
		Long templateId = sendLog.getStepId() != null
			? templateMapper.findTemplateIdByStepId(sendLog.getStepId())
			: templateMapper.findTemplateIdByCampaignId(sendLog.getCampaignId());
		return templateMapper.findById(templateId);
	}

	/**
	 * PL 공용 HMAC 유틸(발송 큐 Plan 15장 Q1, {@link UnsubscribeTokens})로 서명한 토큰을 쓴다.
	 * TEST(customer_id NULL)는 실제 고객이 없어 토큰을 발급할 수 없으므로 예시 토큰을 쓴다
	 * (미리보기·테스트발송 작업과 동일한 처리).
	 */
	private String unsubscribeToken(SendLog sendLog) {
		if (sendLog.getCustomerId() == null) {
			return "example";
		}
		return unsubscribeTokens.issue(sendLog.getSendLogId(), sendLog.getCustomerId());
	}
}
