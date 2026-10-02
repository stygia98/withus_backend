package com.withus.campaign.domain;

import java.time.OffsetDateTime;

/** campaign 테이블 (DB_SCHEMA 4장 9번) */
public class Campaign {

	private Long campaignId;
	private String name;
	private CampaignType type;
	private CampaignStatus status;
	private Long segmentId;
	private Long templateId;
	private Long couponId;
	private OffsetDateTime scheduledAt;
	private TriggerType triggerType;
	private OffsetDateTime startedAt;
	private OffsetDateTime endedAt;
	private Long createdBy;
	private OffsetDateTime createdAt;
	private OffsetDateTime updatedAt;

	public Long getCampaignId() {
		return campaignId;
	}

	public void setCampaignId(Long campaignId) {
		this.campaignId = campaignId;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public CampaignType getType() {
		return type;
	}

	public void setType(CampaignType type) {
		this.type = type;
	}

	public CampaignStatus getStatus() {
		return status;
	}

	public void setStatus(CampaignStatus status) {
		this.status = status;
	}

	public Long getSegmentId() {
		return segmentId;
	}

	public void setSegmentId(Long segmentId) {
		this.segmentId = segmentId;
	}

	public Long getTemplateId() {
		return templateId;
	}

	public void setTemplateId(Long templateId) {
		this.templateId = templateId;
	}

	public Long getCouponId() {
		return couponId;
	}

	public void setCouponId(Long couponId) {
		this.couponId = couponId;
	}

	public OffsetDateTime getScheduledAt() {
		return scheduledAt;
	}

	public void setScheduledAt(OffsetDateTime scheduledAt) {
		this.scheduledAt = scheduledAt;
	}

	public TriggerType getTriggerType() {
		return triggerType;
	}

	public void setTriggerType(TriggerType triggerType) {
		this.triggerType = triggerType;
	}

	public OffsetDateTime getStartedAt() {
		return startedAt;
	}

	public void setStartedAt(OffsetDateTime startedAt) {
		this.startedAt = startedAt;
	}

	public OffsetDateTime getEndedAt() {
		return endedAt;
	}

	public void setEndedAt(OffsetDateTime endedAt) {
		this.endedAt = endedAt;
	}

	public Long getCreatedBy() {
		return createdBy;
	}

	public void setCreatedBy(Long createdBy) {
		this.createdBy = createdBy;
	}

	public OffsetDateTime getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(OffsetDateTime createdAt) {
		this.createdAt = createdAt;
	}

	public OffsetDateTime getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(OffsetDateTime updatedAt) {
		this.updatedAt = updatedAt;
	}
}
