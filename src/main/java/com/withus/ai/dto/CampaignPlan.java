package com.withus.ai.dto;

import java.time.LocalDate;

import com.withus.common.domain.Channel;
import com.withus.coupon.domain.DiscountType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/**
 * AI-04 캠페인 기획 초안 (API_SPEC 11장). 초안 API 의 응답이자, 화면에서 고친 뒤 저장 API 에 그대로 보내는 본문이다.
 * 저장 시 기존 도메인 서비스가 다시 검증하므로 여기서는 형식만 막는다
 */
public record CampaignPlan(
	@NotBlank(message = "캠페인 이름을 입력하세요.") @Size(max = 100, message = "캠페인 이름은 100자 이하예요.") String campaignName,
	@NotNull(message = "채널을 선택하세요.") Channel channel,
	@Valid @NotNull(message = "타겟팅을 입력하세요.") SegmentPart segment,
	@Schema(description = "쿠폰이 없는 캠페인이면 null") @Valid CouponPart coupon,
	@Valid @NotNull(message = "메시지를 입력하세요.") TemplatePart template) {

	public record SegmentPart(
		@NotBlank(message = "타겟팅 이름을 입력하세요.") @Size(max = 100, message = "타겟팅 이름은 100자 이하예요.") String name,
		@NotNull(message = "타겟팅 조건을 입력하세요.") @Schema(description = "DB_SCHEMA 5.1 rule_json 형식") JsonNode rule) {
	}

	public record CouponPart(
		@NotBlank(message = "쿠폰명을 입력하세요.") @Size(max = 100, message = "쿠폰명은 100자 이하예요.") String name,
		@NotNull(message = "할인 유형을 선택하세요.") DiscountType discountType,
		@NotNull(message = "할인 값을 입력하세요.") @Positive(message = "할인 값은 0보다 커야 해요.") Integer discountValue,
		@Positive(message = "최대 할인액은 0보다 커야 해요.") Integer maxDiscountAmount,
		@NotNull(message = "유효기간 시작일을 입력하세요.") LocalDate validFrom,
		@NotNull(message = "유효기간 종료일을 입력하세요.") LocalDate validTo) {
	}

	/** body 는 문단을 빈 줄로 나눈 평문. 저장할 때 메일은 HTML 문단으로 바꾸고 쿠폰 링크를 서버가 붙인다 */
	public record TemplatePart(
		@NotBlank(message = "템플릿 이름을 입력하세요.") @Size(max = 100, message = "템플릿 이름은 100자 이하예요.") String name,
		@Size(max = 200, message = "제목은 200자 이하예요.") String subject,
		@NotBlank(message = "본문을 입력하세요.") @Size(max = 5000, message = "본문은 5000자 이하예요.") String body) {
	}
}
