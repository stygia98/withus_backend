package com.withus.customer.dto;

import java.util.List;

import com.withus.customer.domain.CouponActivity;
import com.withus.customer.domain.SendActivity;

import io.swagger.v3.oas.annotations.media.Schema;

/** 고객 상세의 발송·이벤트·쿠폰 이력 (API_SPEC 3장 GET /customers/{id}/activity) */
public record CustomerActivityResponse(
	@Schema(description = "최근 발송 100건, 최신순. 오픈·클릭은 봇 제외 첫 이벤트 시각") List<SendActivity> sends,
	@Schema(description = "발급 쿠폰 전체, 최신 발급순") List<CouponActivity> coupons) {
}
