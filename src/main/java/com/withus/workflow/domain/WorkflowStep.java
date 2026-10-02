package com.withus.workflow.domain;

import java.time.OffsetDateTime;

/**
 * workflow_step 테이블 (DB_SCHEMA 11번). configJson 은 segment_rule.rule_json 과 같은 패턴으로
 * JSONB 원문 텍스트를 String 으로 들고, 해석은 쓰는 쪽(엔진·빌더 검증)에서 Jackson 으로 한다
 * (DB_SCHEMA 5.2 node_type 별 형식).
 */
public class WorkflowStep {

	private Long stepId;
	private Long campaignId;
	private NodeType nodeType;
	private String configJson;
	private Long nextStepId;
	private Long yesStepId;
	private Long noStepId;
	private short depth;
	private OffsetDateTime createdAt;
	private OffsetDateTime updatedAt;

	public Long getStepId() {
		return stepId;
	}

	public void setStepId(Long stepId) {
		this.stepId = stepId;
	}

	public Long getCampaignId() {
		return campaignId;
	}

	public void setCampaignId(Long campaignId) {
		this.campaignId = campaignId;
	}

	public NodeType getNodeType() {
		return nodeType;
	}

	public void setNodeType(NodeType nodeType) {
		this.nodeType = nodeType;
	}

	public String getConfigJson() {
		return configJson;
	}

	public void setConfigJson(String configJson) {
		this.configJson = configJson;
	}

	public Long getNextStepId() {
		return nextStepId;
	}

	public void setNextStepId(Long nextStepId) {
		this.nextStepId = nextStepId;
	}

	public Long getYesStepId() {
		return yesStepId;
	}

	public void setYesStepId(Long yesStepId) {
		this.yesStepId = yesStepId;
	}

	public Long getNoStepId() {
		return noStepId;
	}

	public void setNoStepId(Long noStepId) {
		this.noStepId = noStepId;
	}

	public short getDepth() {
		return depth;
	}

	public void setDepth(short depth) {
		this.depth = depth;
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
