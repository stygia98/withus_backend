package com.withus.tracking.dto;

import java.time.OffsetDateTime;

/**
 * GET /dashboard/queue (API_SPEC 10장 예시 형식).
 *
 * @param pending          아직 시도하지 않은 PENDING
 * @param sending          SENDING
 * @param retrying         재시도를 기다리는 PENDING (attempt_count > 0)
 * @param ratePerSecond    초당 발송 한도 (ses.max-send-rate)
 * @param expectedEndAt    남은 건(pending + retrying + sending)을 한도로 나눈 예상 종료 시각. 남은 건이 없으면 null
 * @param adSendWindowOpen 지금이 광고성 발송 가능 시간(08:00~20:50)인가
 */
public record QueueStatusResponse(long pending, long sending, long retrying, double ratePerSecond,
	OffsetDateTime expectedEndAt, boolean adSendWindowOpen) {
}
