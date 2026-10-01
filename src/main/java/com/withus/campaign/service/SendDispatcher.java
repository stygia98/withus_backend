package com.withus.campaign.service;

import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
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
import com.withus.customer.service.ConsentService;

/**
 * 발송 큐 디스패처 (발송 큐 Plan 3장) — 선점(tx1, 짧은 UPDATE 한 건으로 자동 커밋)
 * → 트랜잭션 밖에서 TokenBucket 통과·렌더링(MessageComposer)·MessageSender 호출 → 결과 기록(tx2, 역시 단건 UPDATE).
 */
@Component
public class SendDispatcher {

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
	private final TokenBucket tokenBucket;
	private final SendWindow sendWindow;
	private final String trackingBaseUrl;
	private final boolean schedulerEnabled;

	public SendDispatcher(SendLogMapper sendLogMapper, TemplateMapper templateMapper,
			MessageSenderRouter messageSenderRouter, ConsentService consentService, MessageComposer messageComposer,
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
		if (schedulerEnabled) {
			dispatch();
		}
	}

	public void dispatch() {
		int processed = 0;
		while (processed < MAX_PER_RUN) {
			List<SendLog> claimed = sendLogMapper.claimBatch();
			if (claimed.isEmpty()) {
				return;
			}
			for (SendLog sendLog : claimed) {
				processOne(sendLog);
				processed++;
			}
		}
	}

	/** 선점된 한 건을 트랜잭션 밖에서 처리하고 결과를 기록한다 */
	public void processOne(SendLog sendLog) {
		// NOTICE 는 campaign_id 가 없어 템플릿이 없다(F-12, 아직 적재 경로가 없는 미래 작업)
		Template template = sendLog.getKind() == SendKind.NOTICE ? null : resolveTemplate(sendLog);
		if (!recheck(sendLog, template)) {
			return; // recheck 안에서 SKIPPED·PENDING 복귀·시간창 보류를 이미 기록했다
		}
		tokenBucket.acquire();
		Optional<OutboundMessage> message = messageComposer.compose(sendLog, template, unsubscribeUrl(sendLog));
		if (message.isEmpty()) {
			return; // 쿠폰 유효기간 밖 — compose 안에서 이미 SKIPPED(COUPON_INVALID) 기록
		}
		SendResult result = messageSenderRouter.send(message.get());
		if (result.success()) {
			sendLogMapper.recordSent(sendLog.getSendLogId(), result.providerMessageId());
		} else if (result.errorType() == ErrorType.TRANSIENT) {
			retryOrFail(sendLog, result);
		} else {
			sendLogMapper.recordFailed(sendLog.getSendLogId(), result.errorMessage());
		}
	}

	/** 일시 오류(TRANSIENT): 1·5·15분 뒤 재시도, 3회 넘으면 FAILED(발송 큐 Plan 8장) */
	private void retryOrFail(SendLog sendLog, SendResult result) {
		int nextAttemptCount = sendLog.getAttemptCount() + 1;
		if (nextAttemptCount > RETRY_INTERVALS.length) {
			sendLogMapper.recordFailed(sendLog.getSendLogId(), result.errorMessage());
			return;
		}
		OffsetDateTime nextAttemptAt = OffsetDateTime.now(ZoneId.of("Asia/Seoul"))
			.plus(RETRY_INTERVALS[nextAttemptCount - 1]);
		sendLogMapper.recordRetry(sendLog.getSendLogId(), nextAttemptAt, result.errorMessage());
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
			sendLogMapper.recordSkipped(sendLog.getSendLogId());
			return false;
		}
		String campaignStatus = sendLogMapper.findCampaignStatus(sendLog.getCampaignId());
		if ("COMPLETED".equals(campaignStatus)) {
			sendLogMapper.recordSkipped(sendLog.getSendLogId());
			return false;
		}
		if ("PAUSED".equals(campaignStatus)) {
			// 선점(claimBatch)은 PAUSED 를 걸러내지만, 선점 이후 바뀐 경우의 안전장치로 여기서도 본다(Plan 15장 A1)
			sendLogMapper.revertToPending(sendLog.getSendLogId());
			return false;
		}
		boolean adOrNotice = sendLog.getKind() == SendKind.NOTICE || (template != null && template.isAd());
		if (adOrNotice) {
			Optional<OffsetDateTime> holdUntil = sendWindow.holdUntil();
			if (holdUntil.isPresent()) {
				sendLogMapper.holdForSendWindow(sendLog.getSendLogId(), holdUntil.get());
				return false;
			}
		}
		return true;
	}

	private Template resolveTemplate(SendLog sendLog) {
		Long templateId = sendLog.getStepId() != null
			? templateMapper.findTemplateIdByStepId(sendLog.getStepId())
			: templateMapper.findTemplateIdByCampaignId(sendLog.getCampaignId());
		return templateMapper.findById(templateId);
	}

	/**
	 * 임시값: PL 공용 HMAC 수신거부 토큰 유틸(발송 큐 Plan 15장 Q1)이 나오기 전까지
	 * send_log.tracking_token(이미 DB 에서 발급된 고유값)을 그대로 재사용한다.
	 * 유틸이 나오면 서명된 토큰으로 교체한다 (ponytail: 임시값, PL 유틸 도착 시 교체).
	 */
	private String unsubscribeUrl(SendLog sendLog) {
		return trackingBaseUrl + "/unsubscribe/" + sendLog.getTrackingToken();
	}
}
