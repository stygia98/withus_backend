package com.withus.ai.domain;

import java.time.OffsetDateTime;

import lombok.Getter;

/** AI-03 성과 요약 1건. inputJson 은 LLM 에 보낸 집계 지표(개인정보 없음) 그대로 */
@Getter
public class AiReport {

	public static final String CAMPAIGN_SUMMARY = "CAMPAIGN_SUMMARY";

	private Long reportId;
	private Long campaignId;
	private String reportType;
	private String inputJson;
	private String content;
	private String model;
	private OffsetDateTime createdAt;
}
