package com.withus.campaign.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.withus.campaign.domain.CustomerRecipient;
import com.withus.campaign.domain.SendKind;
import com.withus.campaign.domain.SendLog;
import com.withus.campaign.domain.SendStatus;
import com.withus.campaign.mapper.SendLogMapper;
import com.withus.common.domain.Channel;
import com.withus.customer.service.ConsentService;

/**
 * 공통 발송 큐 적재 진입점 (PRD 10.1) — 팀원1(F-12 안내), 팀원3(쿠폰 메일), 워크플로우(SEND 노드), 캠페인(일회성)이 호출한다.
 * 적재 자체는 발송이 아니므로 외부 호출 없이 DB 트랜잭션 안에서 완결된다(발송 큐 Plan 2장).
 */
@Service
public class SendQueueService {

	/** 10만 건 적재가 하나의 긴 트랜잭션으로 묶이지 않도록 나누는 단위 (발송 큐 Plan 2장) */
	private static final int BATCH_SIZE = 500;

	private final SendLogMapper sendLogMapper;
	private final ConsentService consentService;
	private final TransactionTemplate transactionTemplate;

	public SendQueueService(SendLogMapper sendLogMapper, ConsentService consentService,
			PlatformTransactionManager transactionManager) {
		this.sendLogMapper = sendLogMapper;
		this.consentService = consentService;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	/**
	 * 일회성·A/B 캠페인, F-12 수신동의 안내 등 instanceId 없는 적재. 대상을 500건씩 나눠 짧은 트랜잭션으로 적재한다.
	 * 같은 (campaignId, customerId) 로 다시 호출해도 멱등하다(이미 적재된 건 건너뜀).
	 * priority 는 호출자가 넘기지 않고 kind 로 정한다(발송 큐 Plan 2장): TEST 1, NOTICE 2, CAMPAIGN 3 —
	 * (NOTICE, 3) 같은 잘못된 조합을 만들 수 없게 한다.
	 * <p><b>호출자는 @Transactional 안에서 부르면 안 된다</b> — 청크의 TransactionTemplate 이 기본 전파(REQUIRED)라
	 * 호출자 트랜잭션에 합류해 10만 건이 한 트랜잭션이 된다(Plan 2장 "긴 트랜잭션 금지"). 현재 호출자
	 * (CampaignService.start, 예약 스케줄러)는 모두 트랜잭션이 없다. REQUIRES_NEW 로 강제하는 안은 PL 확인 대기
	 * @return 새로 쌓인 건수(PENDING + SKIPPED)
	 */
	public int enqueueOneTime(Long campaignId, List<Long> customerIds, Channel channel, SendKind kind) {
		short priority = priorityOf(kind);
		int totalInserted = 0;
		for (int from = 0; from < customerIds.size(); from += BATCH_SIZE) {
			List<Long> chunk = customerIds.subList(from, Math.min(from + BATCH_SIZE, customerIds.size()));
			totalInserted += transactionTemplate
				.execute(status -> enqueueOneTimeChunk(campaignId, chunk, channel, kind, priority));
		}
		return totalInserted;
	}

	/**
	 * 템플릿 테스트 발송 1건 적재(kind=TEST, priority 1). 고객이 없으므로 수신동의 확인 대상이 아니다.
	 * recipient 는 호출자가 정규화해서 넘긴다
	 */
	public void enqueueTest(long templateId, Channel channel, String recipient) {
		sendLogMapper.insertTest(templateId, recipient, channel, priorityOf(SendKind.TEST));
	}

	private static short priorityOf(SendKind kind) {
		return switch (kind) {
			case TEST -> SendLog.PRIORITY_TEST;
			case NOTICE -> SendLog.PRIORITY_WORKFLOW_OR_NOTICE;
			case CAMPAIGN -> SendLog.PRIORITY_CAMPAIGN_BULK;
		};
	}

	/** SES 반송 웹훅용 — 해당 건을 BOUNCED 로 기록한다 (팀원1, send_log 는 campaign 도메인 소유라 이 서비스를 통해 쓴다) */
	public void markBounced(String providerMessageId) {
		sendLogMapper.markBounced(providerMessageId);
	}

	/** 워크플로우 SEND 노드 적재. 같은 (instanceId, stepId) 로 다시 호출해도 멱등하다 */
	public int enqueueWorkflowStep(long campaignId, long instanceId, long stepId, long customerId, Channel channel) {
		List<SendLog> logs = buildLogs(campaignId, instanceId, stepId, List.of(customerId), channel,
			SendKind.CAMPAIGN, SendLog.PRIORITY_WORKFLOW_OR_NOTICE);
		return logs.isEmpty() ? 0 : sendLogMapper.insertWorkflowBatch(logs);
	}

	private int enqueueOneTimeChunk(Long campaignId, List<Long> customerIds, Channel channel, SendKind kind,
			short priority) {
		List<SendLog> logs = buildLogs(campaignId, null, null, customerIds, channel, kind, priority);
		return logs.isEmpty() ? 0 : sendLogMapper.insertOneTimeBatch(logs);
	}

	private List<SendLog> buildLogs(Long campaignId, Long instanceId, Long stepId, List<Long> customerIds,
			Channel channel, SendKind kind, short priority) {
		// SMS 는 휴대폰이 없는 고객이 recipient=null 로 조회된다. Collectors.toMap 은 null 값에서 NPE 라 먼저 거른다(이슈 #43)
		Map<Long, String> recipients = sendLogMapper.findRecipients(customerIds, channel).stream()
			.filter(r -> r.getRecipient() != null)
			.collect(Collectors.toMap(CustomerRecipient::getCustomerId, CustomerRecipient::getRecipient));
		// 수신동의·suppression·삭제 확인을 청크당 한 번에 한다(고객마다 isSendable 을 부르면 N+1, #21 PL 결정 C)
		Set<Long> sendableIds = consentService.filterSendable(new ArrayList<>(recipients.keySet()), channel);

		List<SendLog> logs = new ArrayList<>();
		for (Long customerId : customerIds) {
			String recipient = recipients.get(customerId);
			if (recipient == null) {
				continue; // 삭제됐거나 해당 채널 연락처가 없다 — 적재하지 않는다
			}
			boolean sendable = sendableIds.contains(customerId);
			logs.add(SendLog.builder()
				.campaignId(campaignId)
				.instanceId(instanceId)
				.stepId(stepId)
				.customerId(customerId)
				.recipient(recipient)
				.channel(channel)
				.status(sendable ? SendStatus.PENDING : SendStatus.SKIPPED)
				.kind(kind)
				.priority(priority)
				.errorMessage(sendable ? null : "NOT_SENDABLE") // 발송 직전 재확인 탈락과 같은 사유 코드
				.build());
		}
		return logs;
	}
}
