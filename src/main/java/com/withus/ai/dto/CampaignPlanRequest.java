package com.withus.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * AI-04 입력 (API_SPEC 11장). 목표 한 줄. 고객 이름·이메일·휴대폰을 넣지 않는다 —
 * 이메일·전화번호 패턴이 있으면 AI 로 보내지 않고 AI_PII_DETECTED(400)로 거절한다.
 */
public record CampaignPlanRequest(
	@NotBlank(message = "목표를 입력하세요.") @Size(max = 300, message = "목표는 300자 이하예요.") String goal) {
}
