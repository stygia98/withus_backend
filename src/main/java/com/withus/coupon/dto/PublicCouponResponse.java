package com.withus.coupon.dto;

import java.time.LocalDate;

import com.withus.common.privacy.Masking;
import com.withus.coupon.domain.CouponIssueRow;
import com.withus.coupon.domain.DiscountType;
import com.withus.coupon.domain.IssueStatus;

/**
 * 고객 쿠폰 카드 (API_SPEC 8장). 이메일·휴대폰은 넣지 않고 이름은 성만 남긴다 (예: 김**).
 * 이름이 없는 고객이면 customerName 은 null — 화면에서 "고객"으로 표시한다.
 */
public record PublicCouponResponse(String customerName, String couponName, DiscountType discountType,
	int discountValue, Integer maxDiscountAmount, LocalDate validFrom, LocalDate validTo, IssueStatus status) {

	public static PublicCouponResponse from(CouponIssueRow row, LocalDate today) {
		return new PublicCouponResponse(Masking.maskName(row.getCustomerName()), row.getCouponName(), row.getDiscountType(),
			row.getDiscountValue(), row.getMaxDiscountAmount(), row.getValidFrom(), row.getValidTo(),
			row.statusOn(today));
	}
}
