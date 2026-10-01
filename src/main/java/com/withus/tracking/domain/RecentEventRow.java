package com.withus.tracking.domain;

import java.time.OffsetDateTime;

import lombok.Getter;

/** 대시보드 최근 이벤트 한 줄 (봇·TEST·NOTICE 제외) */
@Getter
public class RecentEventRow {

	private Long eventId;
	private String eventType;
	private OffsetDateTime occurredAt;
	private Long campaignId;
	private String campaignName;
	/** 이름이 없는 고객이면 null */
	private String customerName;
}
