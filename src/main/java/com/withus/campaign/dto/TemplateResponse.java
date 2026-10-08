package com.withus.campaign.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.withus.campaign.domain.Template;
import com.withus.common.domain.Channel;

/** inUse 는 상세 조회에서만 채운다 (목록은 null → 응답에서 생략) */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TemplateResponse(long templateId, Channel channel, String name, String subject, String body,
	String adYn, OffsetDateTime createdAt, OffsetDateTime updatedAt, Boolean inUse) {

	public static TemplateResponse from(Template template) {
		return from(template, null);
	}

	public static TemplateResponse from(Template template, Boolean inUse) {
		return new TemplateResponse(template.getTemplateId(), template.getChannel(), template.getName(),
			template.getSubject(), template.getBody(), template.getAdYn(), template.getCreatedAt(),
			template.getUpdatedAt(), inUse);
	}
}
