package com.withus.campaign.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.withus.common.domain.Channel;

import lombok.Builder;
import lombok.Getter;

/** send_log 테이블 (DB_SCHEMA 13번, 발송 큐 겸 이력) */
@Getter
@Builder
public class SendLog {

	/** 발송 큐 Plan 2장: priority 는 kind·instanceId 로 결정한다 (TEST, WORKFLOW·NOTICE, CAMPAIGN 대량) */
	public static final short PRIORITY_TEST = 1;
	public static final short PRIORITY_WORKFLOW_OR_NOTICE = 2;
	public static final short PRIORITY_CAMPAIGN_BULK = 3;

	private Long sendLogId;
	private Long campaignId;
	private Long instanceId;
	private Long stepId;
	private Long customerId;
	private String recipient;
	private Channel channel;
	private String abVariant;
	private SendStatus status;
	private SendKind kind;
	private short priority;
	private String providerMessageId;
	private UUID trackingToken;
	private int attemptCount;
	private OffsetDateTime nextAttemptAt;
	private String errorMessage;
	private OffsetDateTime sentAt;
	private OffsetDateTime createdAt;
	private OffsetDateTime updatedAt;
	/** TEST 발송만 값이 있다 — 캠페인·단계가 없어 렌더링할 템플릿을 여기서 찾는다 */
	private Long templateId;
}
