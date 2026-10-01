package com.withus.tracking.domain;

import java.time.OffsetDateTime;

import lombok.Getter;

/**
 * 링크 치환 대상 발송 건: 추적 토큰과 사용한 템플릿.
 * 템플릿은 일회성이면 campaign.template_id, 워크플로우면 workflow_step.config_json 의 templateId (DB_SCHEMA 5.2).
 * TEST·NOTICE 처럼 캠페인이 없으면 templateId 가 null 이다.
 */
@Getter
public class TrackingTarget {

	private String trackingToken;
	private Long templateId;
	/** 템플릿이 바뀌면 링크 캐시를 다시 만든다 */
	private OffsetDateTime templateUpdatedAt;
}
