package com.withus.coupon.domain;

import java.time.LocalDate;

/** 생성·수정 요청을 검증한 뒤 저장에 쓰는 값. 정액이면 maxDiscountAmount 는 null 로 맞춘다 */
public record CouponDraft(String name, DiscountType discountType, int discountValue, Integer maxDiscountAmount,
	LocalDate validFrom, LocalDate validTo) {
}
