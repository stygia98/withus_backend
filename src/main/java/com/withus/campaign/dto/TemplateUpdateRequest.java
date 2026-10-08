package com.withus.campaign.dto;

import com.withus.campaign.domain.Template;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** channel 은 생성 후 바꾸지 않는다 */
public record TemplateUpdateRequest(
	@NotBlank(message = "이름을 입력하세요.") String name,
	String subject,
	@NotBlank(message = "본문을 입력하세요.") String body,
	@NotBlank @Pattern(regexp = "[YN]", message = "adYn 은 Y 또는 N 입니다.") String adYn) {

	public Template toTemplate() {
		Template template = new Template();
		template.setName(name);
		template.setSubject(subject);
		template.setBody(body);
		template.setAdYn(adYn);
		return template;
	}
}
