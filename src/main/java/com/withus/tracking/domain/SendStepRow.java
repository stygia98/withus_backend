package com.withus.tracking.domain;

import lombok.Getter;

/** 워크플로우 발송 단계 1개. 템플릿이 지워졌거나 설정이 비어 있으면 templateId·templateName 은 null */
@Getter
public class SendStepRow {

	private Long stepId;
	private String nodeType;
	private Long templateId;
	private String templateName;
	private Long couponId;
}
