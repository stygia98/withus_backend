package com.withus.coupon.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import lombok.Getter;

/** 쿠폰 정의 + 발급·사용 수 (목록·상세 조회용) */
@Getter
public class Coupon {

	private Long couponId;
	private String name;
	private DiscountType discountType;
	private Integer discountValue;
	/** 정률 상한. 정액이면 null */
	private Integer maxDiscountAmount;
	private LocalDate validFrom;
	private LocalDate validTo;
	private Long issuedCount;
	private Long usedCount;
	private OffsetDateTime createdAt;
	private OffsetDateTime updatedAt;
}
