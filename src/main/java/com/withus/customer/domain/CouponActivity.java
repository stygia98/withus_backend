package com.withus.customer.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import lombok.Getter;

/** 고객 상세의 쿠폰 발급 이력 한 건 (coupon_issue·coupon 조회, 쓰기 없음). status: USABLE, USED, EXPIRED, NOT_STARTED (오늘 기준) */
@Getter
public class CouponActivity {

	private Long issueId;
	private Long couponId;
	private String couponName;
	private String status;
	private LocalDate validFrom;
	private LocalDate validTo;
	private OffsetDateTime issuedAt;
	private OffsetDateTime usedAt;
}
