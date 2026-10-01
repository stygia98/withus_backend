package com.withus.coupon.dto;

import java.time.LocalDate;

import com.withus.coupon.domain.CouponDraft;
import com.withus.coupon.domain.DiscountType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 쿠폰 생성·수정 공용 요청 (API_SPEC 7장). 정률 상한·기간 순서는 서비스에서 도메인 오류 코드로 검사한다 */
public record CouponRequest(
	@NotBlank(message = "쿠폰명을 입력하세요.") @Size(max = 100, message = "쿠폰명은 100자 이하입니다.") String name,
	@NotNull(message = "할인 유형을 선택하세요.") DiscountType discountType,
	@NotNull(message = "할인 값을 입력하세요.") @Positive(message = "할인 값은 0보다 커야 합니다.") Integer discountValue,
	@Positive(message = "최대 할인액은 0보다 커야 합니다.") Integer maxDiscountAmount,
	@NotNull(message = "유효기간 시작일을 입력하세요.") LocalDate validFrom,
	@NotNull(message = "유효기간 종료일을 입력하세요.") LocalDate validTo) {

	public CouponDraft toDraft() {
		return new CouponDraft(name, discountType, discountValue, maxDiscountAmount, validFrom, validTo);
	}
}
