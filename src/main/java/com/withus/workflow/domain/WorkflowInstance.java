package com.withus.workflow.domain;

import java.time.OffsetDateTime;

/** workflow_instance 테이블 (DB_SCHEMA 12번) — 고객별 워크플로우 진행 상태 */
public class WorkflowInstance {

	private Long instanceId;
	private Long campaignId;
	private Long customerId;
	private Long currentStepId;
	private InstanceStatus status;
	private OffsetDateTime nextRunAt;
	private int retryCount;
	private String lastError;
	private OffsetDateTime createdAt;
	private OffsetDateTime updatedAt;

	public Long getInstanceId() {
		return instanceId;
	}

	public void setInstanceId(Long instanceId) {
		this.instanceId = instanceId;
	}

	public Long getCampaignId() {
		return campaignId;
	}

	public void setCampaignId(Long campaignId) {
		this.campaignId = campaignId;
	}

	public Long getCustomerId() {
		return customerId;
	}

	public void setCustomerId(Long customerId) {
		this.customerId = customerId;
	}

	public Long getCurrentStepId() {
		return currentStepId;
	}

	public void setCurrentStepId(Long currentStepId) {
		this.currentStepId = currentStepId;
	}

	public InstanceStatus getStatus() {
		return status;
	}

	public void setStatus(InstanceStatus status) {
		this.status = status;
	}

	public OffsetDateTime getNextRunAt() {
		return nextRunAt;
	}

	public void setNextRunAt(OffsetDateTime nextRunAt) {
		this.nextRunAt = nextRunAt;
	}

	public int getRetryCount() {
		return retryCount;
	}

	public void setRetryCount(int retryCount) {
		this.retryCount = retryCount;
	}

	public String getLastError() {
		return lastError;
	}

	public void setLastError(String lastError) {
		this.lastError = lastError;
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
