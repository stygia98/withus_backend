package com.withus.ai.dto;

import com.withus.segment.dto.SegmentPreviewResponse;

import io.swagger.v3.oas.annotations.media.Schema;

/** AI-04 초안 응답. 저장 전이라 id 가 없다. target 은 초안 규칙으로 센 현재 대상 수 */
public record CampaignPlanResponse(CampaignPlan plan,
	@Schema(description = "타겟팅 규칙의 현재 대상 수 (세그먼트 미리보기와 같은 형식)") SegmentPreviewResponse target) {
}
