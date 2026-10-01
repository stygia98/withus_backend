package com.withus.tracking.domain;

import lombok.Getter;

/** 발송 큐 현황 원값. 큐는 운영 상태라 TEST·NOTICE 도 포함한다(같은 큐를 쓰므로) */
@Getter
public class QueueCounts {

	/** PENDING 이며 아직 시도하지 않은 건 */
	private long pending;
	private long sending;
	/** PENDING 이며 일시 오류로 재시도를 기다리는 건 (attempt_count > 0) */
	private long retrying;
}
