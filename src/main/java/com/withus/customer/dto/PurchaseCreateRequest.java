package com.withus.customer.dto;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 구매 등록 (API_SPEC 3장 POST /customers/{id}/purchases) */
public record PurchaseCreateRequest(
	@NotNull(message = "금액을 입력하세요.") @Positive(message = "금액은 0보다 커야 합니다.") Long amount,
	@Schema(description = "이 고객에게 발급됐고, 미사용이며, 구매일이 유효기간 안인 쿠폰. 없으면 쿠폰 미사용", example = "318")
	Long couponIssueId,
	@Schema(description = "없으면 지금", example = "2026-10-02T14:10:00+09:00") OffsetDateTime purchasedAt) {
}
