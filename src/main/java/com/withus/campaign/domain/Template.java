package com.withus.campaign.domain;

import java.time.OffsetDateTime;

import com.withus.common.domain.Channel;

/** template 테이블 (DB_SCHEMA 4장 7번) */
public class Template {

	private Long templateId;
	private Channel channel;
	private String name;
	private String subject;
	private String body;
	private String adYn;
	private Long createdBy;
	private OffsetDateTime createdAt;
	private OffsetDateTime updatedAt;

	public boolean isAd() {
		return "Y".equals(adYn);
	}

	public Long getTemplateId() {
		return templateId;
	}

	public void setTemplateId(Long templateId) {
		this.templateId = templateId;
	}

	public Channel getChannel() {
		return channel;
	}

	public void setChannel(Channel channel) {
		this.channel = channel;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getSubject() {
		return subject;
	}

	public void setSubject(String subject) {
		this.subject = subject;
	}

	public String getBody() {
		return body;
	}

	public void setBody(String body) {
		this.body = body;
	}

	public String getAdYn() {
		return adYn;
	}

	public void setAdYn(String adYn) {
		this.adYn = adYn;
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
