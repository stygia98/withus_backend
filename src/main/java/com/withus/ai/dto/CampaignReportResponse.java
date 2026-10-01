package com.withus.ai.dto;

import java.time.OffsetDateTime;

import tools.jackson.databind.JsonNode;

/**
 * AI-03 성과 요약 (API_SPEC 11장).
 *
 * @param input   요약에 쓴 집계 지표 (생성 시점 값 — 이후 지표가 바뀌면 재생성으로 갱신)
 * @param model   사용한 모델. 성공 발송이 없어 LLM 을 부르지 않았으면 "none"
 */
public record CampaignReportResponse(long reportId, long campaignId, String content, String model, JsonNode input,
	OffsetDateTime createdAt) {
}
