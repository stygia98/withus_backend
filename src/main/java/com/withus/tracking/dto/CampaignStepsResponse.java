package com.withus.tracking.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * GET /analytics/campaigns/{campaignId}/steps (API_SPEC 10장). 워크플로우 발송 단계별 KPI.
 * 지표 정의는 캠페인 KPI 와 같다. 일회성 캠페인이면 steps 는 빈 배열. from·to 는 요청한 기간(없으면 null = 전체).
 */
public record CampaignStepsResponse(long campaignId, String name, String type, LocalDate from, LocalDate to,
	List<StepAnalytics> steps) {

	/**
	 * @param nodeType SEND_EMAIL / SEND_SMS
	 * @param couponId 이 단계에 연결된 쿠폰 (없으면 null)
	 */
	public record StepAnalytics(long stepId, String nodeType, Long templateId, String templateName, Long couponId,
		SendKpi kpi) {
	}
}
