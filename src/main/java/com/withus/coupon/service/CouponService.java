package com.withus.coupon.service;

import java.util.UUID;

/**
 * 구간 간 연결 인터페이스 (PRD 10.1) — 제공: 팀원3(전환), 호출: 팀원2(발송 직전 발급), 팀원1(구매 등록)
 * 시그니처 확정. 변경은 PL 리뷰로만 한다.
 */
public interface CouponService {

	/**
	 * 쿠폰을 발급하고 고객 페이지 토큰(/c/[token])을 돌려준다. SKIPPED 건에는 호출하지 않는다.
	 *
	 * <p><b>멱등:</b> 같은 sendLogId 로 다시 호출하면 새로 발급하지 않고 기존 발급의 토큰을 그대로 돌려준다.
	 * 발송 큐가 일시 오류로 건을 PENDING 으로 되돌려 렌더링부터 다시 하기 때문이다 (PRD 8.2).
	 * 발송 1건당 발급 1건이라는 규칙은 coupon_issue.send_log_id UNIQUE 로도 보장된다.
	 *
	 * <p>구현 시 주의: 먼저 조회하고 없을 때만 넣는 방식은 동시 호출에서 제약 위반이 나므로,
	 * INSERT ... ON CONFLICT (send_log_id) DO NOTHING 후 send_log_id 로 다시 조회한다.
	 */
	UUID issue(long couponId, long customerId, long sendLogId);

	/** 구매 등록 시 발급 쿠폰을 사용 처리한다. 이미 사용됐으면 예외 */
	void markUsed(long couponIssueId);
}
