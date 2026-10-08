package com.withus.campaign.dto;

import java.time.OffsetDateTime;

import com.withus.campaign.domain.Campaign;
import com.withus.campaign.domain.CampaignStatus;
import com.withus.campaign.domain.CampaignType;
import com.withus.campaign.domain.TriggerType;

public record CampaignResponse(long campaignId, String name, CampaignType type, CampaignStatus status,
	long segmentId, Long templateId, Long couponId, OffsetDateTime scheduledAt, TriggerType triggerType,
	OffsetDateTime startedAt, OffsetDateTime endedAt, OffsetDateTime createdAt, OffsetDateTime updatedAt) {

	public static CampaignResponse from(Campaign campaign) {
		return new CampaignResponse(campaign.getCampaignId(), campaign.getName(), campaign.getType(),
			campaign.getStatus(), campaign.getSegmentId(), campaign.getTemplateId(), campaign.getCouponId(),
			campaign.getScheduledAt(), campaign.getTriggerType(), campaign.getStartedAt(), campaign.getEndedAt(),
			campaign.getCreatedAt(), campaign.getUpdatedAt());
	}
}
