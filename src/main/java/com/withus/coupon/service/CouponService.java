package com.withus.coupon.service;

import java.util.UUID;

/**
 * 구간 간 연결 인터페이스 (PRD 10.1) — 제공: 팀원3(전환), 호출: 팀원2(발송 직전 발급), 팀원1(구매 등록)
 * 시그니처 변경은 W1 합의 후 PL 리뷰로만 한다.
 */
public interface CouponService {

	/** 쿠폰을 발급하고 고객 페이지 토큰(/c/[token])을 돌려준다. SKIPPED 건에는 호출하지 않는다 */
	UUID issue(long couponId, long customerId, long sendLogId);

	/** 구매 등록 시 발급 쿠폰을 사용 처리한다. 이미 사용됐으면 예외 */
	void markUsed(long couponIssueId);
}
