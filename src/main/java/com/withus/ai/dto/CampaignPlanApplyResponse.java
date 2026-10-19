package com.withus.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** AI-04 저장 결과. 캠페인은 DRAFT 다 */
public record CampaignPlanApplyResponse(long campaignId, long segmentId,
	@Schema(description = "쿠폰 없는 초안이면 null") Long couponId, long templateId) {
}
