package com.withus.customer.domain;

import java.time.OffsetDateTime;

import com.withus.common.domain.Channel;

import lombok.Getter;

/** consent_history 테이블 (DB_SCHEMA 3번) */
@Getter
public class ConsentHistory {

	private Long historyId;
	private Channel channel;
	private String beforeYn;
	private String afterYn;
	private String source;
	private String note;
	private OffsetDateTime changedAt;
}
