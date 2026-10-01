package com.withus.customer.domain;

import java.time.OffsetDateTime;

import lombok.Getter;

/** purchase 테이블 (DB_SCHEMA). couponName 은 coupon 조인 값 */
@Getter
public class Purchase {

	private Long purchaseId;
	private Long amount;
	private Long couponIssueId;
	private String couponName;
	private OffsetDateTime purchasedAt;
}
