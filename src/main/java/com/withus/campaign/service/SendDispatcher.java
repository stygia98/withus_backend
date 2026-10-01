package com.withus.campaign.service;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.withus.campaign.domain.SendKind;
import com.withus.campaign.domain.SendLog;
import com.withus.campaign.domain.Template;
import com.withus.campaign.mapper.SendLogMapper;
import com.withus.campaign.mapper.TemplateMapper;
import com.withus.campaign.service.messaging.MessageSenderRouter;
import com.withus.campaign.service.messaging.OutboundMessage;
import com.withus.campaign.service.messaging.SendResult;
import com.withus.customer.service.ConsentService;

/**
 * 발송 큐 디스패처 (발송 큐 Plan 3장) — 선점(tx1, 짧은 UPDATE 한 건으로 자동 커밋)
 * → 트랜잭션 밖에서 TokenBucket 통과·MessageSender 호출 → 결과 기록(tx2, 역시 단건 UPDATE).
 * 발송 직전 재확인(SendRecheck)·렌더링(MessageComposer)·재시도(TRANSIENT 백오프)는 후속 작업에서
 * processOne 에 끼워 넣는다 — 지금은 템플릿 원문을 그대로 보낸다
 * (ponytail: 치환자·광고문구·추적 링크 미적용, 렌더링 작업에서 교체).
 */
@Component
public class SendDispatcher {

	/** 한 번 실행(dispatch 호출)에 처리할 건수 상한 — 스케줄러 풀(5)을 오래 점유하지 않게 한다 */
	private static final int MAX_PER_RUN = 200;

	private final SendLogMapper sendLogMapper;
	private final TemplateMapper templateMapper;
	private final MessageSenderRouter messageSenderRouter;
	private final ConsentService consentService;
	private final TokenBucket tokenBucket;
	private final SendWindow sendWindow;
	private final boolean schedulerEnabled;

	public SendDispatcher(SendLogMapper sendLogMapper, TemplateMapper templateMapper,
			MessageSenderRouter messageSenderRouter, ConsentService consentService,
			@Value("${ses.max-send-rate}") int maxSendRate,
			@Value("${withus.send-window.start}") String sendWindowStart,
			@Value("${withus.send-window.end}") String sendWindowEnd,
			@Value("${withus.scheduler.send-dispatcher.enabled:true}") boolean schedulerEnabled) {
		this.sendLogMapper = sendLogMapper;
		this.templateMapper = templateMapper;
		this.messageSenderRouter = messageSenderRouter;
		this.consentService = consentService;
		this.tokenBucket = new TokenBucket(maxSendRate);
		this.sendWindow = new SendWindow(LocalTime.parse(sendWindowStart), LocalTime.parse(sendWindowEnd));
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
		OutboundMessage message = buildMessage(sendLog, template);
		SendResult result = messageSenderRouter.send(message);
		if (result.success()) {
			sendLogMapper.recordSent(sendLog.getSendLogId(), result.providerMessageId());
		} else {
			sendLogMapper.recordFailed(sendLog.getSendLogId(), result.errorMessage());
		}
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

	private OutboundMessage buildMessage(SendLog sendLog, Template template) {
		return new OutboundMessage(sendLog.getChannel(), sendLog.getRecipient(), template.getSubject(),
			template.getBody(), Map.of());
	}
}
