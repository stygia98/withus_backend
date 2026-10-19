package com.withus.coupon.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import com.withus.common.privacy.Masking;
import com.withus.coupon.domain.CouponIssueRow;
import com.withus.coupon.domain.DiscountType;
import com.withus.coupon.domain.IssueStatus;

/**
 * 매장 바코드 조회·사용 처리 응답 (API_SPEC 8장). 직원이 쿠폰 내용과 상태를 확인하는 화면용이라
 * 고객은 이름의 성만 보여 주고(예: 김**) 이메일·휴대폰은 넣지 않는다. usedAt 은 사용 전이면 null.
 */
public record CouponRedeemResponse(String code, String customerName, String couponName, DiscountType discountType,
	int discountValue, Integer maxDiscountAmount, LocalDate validFrom, LocalDate validTo, IssueStatus status,
	OffsetDateTime usedAt) {

	public static CouponRedeemResponse from(CouponIssueRow row, LocalDate today) {
		return new CouponRedeemResponse(row.getCode(), Masking.maskName(row.getCustomerName()), row.getCouponName(),
			row.getDiscountType(), row.getDiscountValue(), row.getMaxDiscountAmount(), row.getValidFrom(),
			row.getValidTo(), row.statusOn(today), row.getUsedAt());
	}
}
