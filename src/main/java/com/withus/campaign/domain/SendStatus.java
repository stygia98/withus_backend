package com.withus.campaign.domain;

/** send_log.status (DB_SCHEMA 13번, 발송 큐 Plan 1장 상태 전이) */
public enum SendStatus {
	PENDING, SENDING, SENT, FAILED, SKIPPED, BOUNCED
}
