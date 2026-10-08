package com.withus.campaign.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;

/** API_SPEC 6장 GET /campaigns/{id}/estimate 응답. reason·nextAvailableAt 은 allowed=false 일 때만 채운다 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CampaignEstimateResponse(long targetCount, long pendingBacklog, int ratePerSecond,
	OffsetDateTime expectedEndAt, String adYn, boolean allowed, String reason, OffsetDateTime nextAvailableAt) {
}
