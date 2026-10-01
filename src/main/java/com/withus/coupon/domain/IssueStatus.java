package com.withus.coupon.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 발급 쿠폰 상태 (API_SPEC 8장). 저장하지 않고 used_at 과 유효기간으로 매번 계산한다.
 * 사용 완료가 기간보다 우선한다 — 기간이 지나도 이미 쓴 쿠폰은 USED 로 보인다.
 */
public enum IssueStatus {
	USABLE,
	USED,
	EXPIRED,
	NOT_STARTED;

	public static IssueStatus of(OffsetDateTime usedAt, LocalDate validFrom, LocalDate validTo, LocalDate today) {
		if (usedAt != null) {
			return USED;
		}
		if (today.isBefore(validFrom)) {
			return NOT_STARTED;
		}
		if (today.isAfter(validTo)) {
			return EXPIRED;
		}
		return USABLE;
	}
}
