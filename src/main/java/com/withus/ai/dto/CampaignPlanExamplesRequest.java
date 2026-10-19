package com.withus.ai.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** AI-04 예시 새로 만들기. current 는 지금 화면에 보이는 예시(겹치지 않게 LLM 에 함께 보낸다) */
public record CampaignPlanExamplesRequest(
	@Schema(description = "지금 보이는 예시. 없으면 null") @Size(max = 10, message = "예시는 10개 이하예요.")
	List<@Size(max = 100, message = "예시는 100자 이하예요.") String> current) {
}
