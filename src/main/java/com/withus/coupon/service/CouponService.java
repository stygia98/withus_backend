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
	 * 다른 couponId·customerId 로 다시 호출하는 경로는 정상 흐름에 없으며, 이때도 처음 발급분의 토큰을 돌려준다
	 * (인자를 무시, project #12 PL 결정).
	 *
	 * <p>구현 시 주의: 먼저 조회하고 없을 때만 넣는 방식은 동시 호출에서 제약 위반이 나므로,
	 * INSERT ... ON CONFLICT (send_log_id) DO NOTHING 후 send_log_id 로 다시 조회한다.
	 */
	UUID issue(long couponId, long customerId, long sendLogId);

	/**
	 * 구매 등록 시 발급 쿠폰을 사용 처리한다. 미사용인지만 원자적으로 확인한다.
	 *
	 * <p><b>유효기간은 호출하는 쪽이 판정한다.</b> 구매 등록은 오늘이 아니라 구매일 기준으로 기간을 보므로
	 * (PRD F-10 ①, 팀원1 PurchaseService), 이 메서드는 기간을 검사하지 않는다. 고객 사용하기(/c/[token])는
	 * 오늘 기준으로 따로 검사한다. 고객 일치 확인도 호출하는 쪽이 한다.
	 *
	 * @throws com.withus.common.exception.BusinessException COUPON_ALREADY_USED(409) 이미 사용,
	 *                                                       COUPON_NOT_FOUND(404) 없는 발급
	 */
	void markUsed(long couponIssueId);
}
