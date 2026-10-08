package com.withus.campaign.dto;

import com.withus.campaign.domain.Campaign;
import com.withus.campaign.domain.CampaignType;
import com.withus.campaign.domain.TriggerType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * ONE_TIME: templateId 필수, triggerType 없음. WORKFLOW: triggerType 필수, templateId·couponId 없음
 * (DB_SCHEMA ck_campaign_type_fields 와 같은 규칙). type 별 필드 조합은 서비스에서 검증한다
 */
public record CampaignCreateRequest(
	@NotNull(message = "유형을 선택하세요.") CampaignType type,
	@NotBlank(message = "이름을 입력하세요.") String name,
	@NotNull(message = "세그먼트를 선택하세요.") Long segmentId,
	Long templateId,
	Long couponId,
	TriggerType triggerType) {

	public Campaign toCampaign() {
		Campaign campaign = new Campaign();
		campaign.setType(type);
		campaign.setName(name);
		campaign.setSegmentId(segmentId);
		campaign.setTemplateId(templateId);
		campaign.setCouponId(couponId);
		campaign.setTriggerType(triggerType);
		return campaign;
	}
}
