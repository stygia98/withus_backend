package com.withus.campaign.dto;

import com.withus.campaign.domain.Template;
import com.withus.common.domain.Channel;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record TemplateCreateRequest(
	@NotNull(message = "채널을 선택하세요.") Channel channel,
	@NotBlank(message = "이름을 입력하세요.") String name,
	String subject,
	@NotBlank(message = "본문을 입력하세요.") String body,
	@NotBlank @Pattern(regexp = "[YN]", message = "adYn 은 Y 또는 N 입니다.") String adYn) {

	public Template toTemplate() {
		Template template = new Template();
		template.setChannel(channel);
		template.setName(name);
		template.setSubject(subject);
		template.setBody(body);
		template.setAdYn(adYn);
		return template;
	}
}
