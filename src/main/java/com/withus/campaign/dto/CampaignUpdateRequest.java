package com.withus.campaign.dto;

import com.withus.campaign.domain.Campaign;
import com.withus.campaign.domain.TriggerType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** type 은 생성 후 바꾸지 않는다. DRAFT 상태에서만 호출된다(CampaignService) */
public record CampaignUpdateRequest(
	@NotBlank(message = "이름을 입력하세요.") String name,
	@NotNull(message = "세그먼트를 선택하세요.") Long segmentId,
	Long templateId,
	Long couponId,
	TriggerType triggerType) {

	public Campaign toCampaign() {
		Campaign campaign = new Campaign();
		campaign.setName(name);
		campaign.setSegmentId(segmentId);
		campaign.setTemplateId(templateId);
		campaign.setCouponId(couponId);
		campaign.setTriggerType(triggerType);
		return campaign;
	}
}
