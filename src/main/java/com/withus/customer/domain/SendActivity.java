package com.withus.customer.domain;

import java.time.OffsetDateTime;

import lombok.Getter;

/**
 * 고객 상세의 발송 이력 한 건 (send_log·campaign·track_event 조회, 쓰기 없음).
 * openedAt·clickedAt 은 봇 제외 첫 이벤트 시각 (CLAUDE.md 6장 8번)
 */
@Getter
public class SendActivity {

	private Long sendLogId;
	private Long campaignId;
	private String campaignName;
	private String channel;
	private String kind;
	private String status;
	private String errorMessage;
	private OffsetDateTime createdAt;
	private OffsetDateTime sentAt;
	private OffsetDateTime openedAt;
	private OffsetDateTime clickedAt;
}
