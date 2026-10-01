package com.withus.tracking.domain;

import lombok.Getter;

/** 발송·반응 집계 원값 (비율 계산 전). TEST·NOTICE 와 봇 이벤트는 쿼리에서 이미 빠져 있다 */
@Getter
public class SendStats {

	/** 발송 시도: SENT + BOUNCED + FAILED */
	private long attempted;
	/** 발송 성공: SENT */
	private long sent;
	/** 성공 발송 중 사람 OPEN 이 있는 고유 고객 수 */
	private long uniqueOpens;
	/** 성공 발송 중 사람 CLICK 이 있는 고유 고객 수 */
	private long uniqueClicks;
	/** 성공 발송으로 받은 쿠폰을 사용한 고유 고객 수 */
	private long couponUsed;
}
