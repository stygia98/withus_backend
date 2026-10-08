package com.withus.coupon.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import com.withus.coupon.domain.CouponIssueRow;
import com.withus.coupon.domain.DiscountType;
import com.withus.coupon.domain.IssueStatus;

/** 고객별 발급 쿠폰 (구매 등록 화면의 쿠폰 선택, 고객 상세 쿠폰 이력) */
public record CustomerCouponResponse(long issueId, long couponId, String couponName, DiscountType discountType,
	int discountValue, Integer maxDiscountAmount, LocalDate validFrom, LocalDate validTo, OffsetDateTime issuedAt,
	OffsetDateTime usedAt, IssueStatus status) {

	public static CustomerCouponResponse from(CouponIssueRow row, LocalDate today) {
		return new CustomerCouponResponse(row.getIssueId(), row.getCouponId(), row.getCouponName(),
			row.getDiscountType(), row.getDiscountValue(), row.getMaxDiscountAmount(), row.getValidFrom(),
			row.getValidTo(), row.getIssuedAt(), row.getUsedAt(), row.statusOn(today));
	}
}
