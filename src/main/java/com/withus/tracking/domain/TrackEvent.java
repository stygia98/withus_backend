package com.withus.tracking.domain;

import java.time.OffsetDateTime;

import lombok.Getter;
import lombok.Setter;

/** track_event 테이블 (DB_SCHEMA 14·15번) */
@Getter
@Setter
public class TrackEvent {

	public static final String OPEN = "OPEN";
	public static final String CLICK = "CLICK";

	private Long eventId;
	private Long sendLogId;
	private String eventType;
	/** CLICK 만 값이 있다 */
	private Long linkId;
	private String userAgent;
	/** SHA-256 hex (64자) */
	private String ipHash;
	/** 'Y' 면 봇 이벤트 — 모든 지표와 워크플로우 분기에서 제외 */
	private String botYn;
	private OffsetDateTime occurredAt;
}
