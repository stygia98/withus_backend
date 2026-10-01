package com.withus.coupon.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import lombok.Getter;

/** 발급 1건 + 쿠폰 정의 (발급 목록·고객별 쿠폰·고객 쿠폰 페이지 공용) */
@Getter
public class CouponIssueRow {

	private Long issueId;
	private Long couponId;
	private Long customerId;
	/** 이름이 없는 고객이면 null */
	private String customerName;
	private Long sendLogId;
	private OffsetDateTime issuedAt;
	private OffsetDateTime usedAt;

	private String couponName;
	private DiscountType discountType;
	private Integer discountValue;
	private Integer maxDiscountAmount;
	private LocalDate validFrom;
	private LocalDate validTo;

	public IssueStatus statusOn(LocalDate today) {
		return IssueStatus.of(usedAt, validFrom, validTo, today);
	}
}
