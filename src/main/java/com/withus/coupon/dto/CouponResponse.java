package com.withus.coupon.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import com.withus.coupon.domain.Coupon;
import com.withus.coupon.domain.DiscountType;

/** 쿠폰 목록·상세. issuedCount 가 0보다 크면 수정은 종료일 연장만 가능하다 */
public record CouponResponse(long couponId, String name, DiscountType discountType, int discountValue,
	Integer maxDiscountAmount, LocalDate validFrom, LocalDate validTo, long issuedCount, long usedCount,
	OffsetDateTime createdAt, OffsetDateTime updatedAt) {

	public static CouponResponse from(Coupon c) {
		return new CouponResponse(c.getCouponId(), c.getName(), c.getDiscountType(), c.getDiscountValue(),
			c.getMaxDiscountAmount(), c.getValidFrom(), c.getValidTo(), c.getIssuedCount(), c.getUsedCount(),
			c.getCreatedAt(), c.getUpdatedAt());
	}
}
