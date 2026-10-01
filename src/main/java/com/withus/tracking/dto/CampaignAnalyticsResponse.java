package com.withus.tracking.dto;

import java.time.LocalDate;
import java.util.List;

/** GET /analytics/campaigns/{campaignId} (API_SPEC 10장 예시 형식). from·to 는 요청한 기간(없으면 null = 전체) */
public record CampaignAnalyticsResponse(long campaignId, String name, LocalDate from, LocalDate to, SendKpi kpi,
	List<FunnelStage> funnel) {

	/** 전환 흐름 한 단계: ATTEMPTED → SENT → OPENED → CLICKED → CONVERTED */
	public record FunnelStage(String stage, long count) {
	}

	public static CampaignAnalyticsResponse of(long campaignId, String name, LocalDate from, LocalDate to,
		SendKpi kpi) {
		return new CampaignAnalyticsResponse(campaignId, name, from, to, kpi, List.of(
			new FunnelStage("ATTEMPTED", kpi.attempted()),
			new FunnelStage("SENT", kpi.sent()),
			new FunnelStage("OPENED", kpi.uniqueOpens()),
			new FunnelStage("CLICKED", kpi.uniqueClicks()),
			new FunnelStage("CONVERTED", kpi.couponUsed())));
	}
}
