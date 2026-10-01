package com.withus.tracking.dto;

import java.util.List;

/** GET /analytics/campaigns/{campaignId} (API_SPEC 10장 예시 형식) */
public record CampaignAnalyticsResponse(long campaignId, String name, SendKpi kpi, List<FunnelStage> funnel) {

	/** 전환 흐름 한 단계: ATTEMPTED → SENT → OPENED → CLICKED → CONVERTED */
	public record FunnelStage(String stage, long count) {
	}

	public static CampaignAnalyticsResponse of(long campaignId, String name, SendKpi kpi) {
		return new CampaignAnalyticsResponse(campaignId, name, kpi, List.of(
			new FunnelStage("ATTEMPTED", kpi.attempted()),
			new FunnelStage("SENT", kpi.sent()),
			new FunnelStage("OPENED", kpi.uniqueOpens()),
			new FunnelStage("CLICKED", kpi.uniqueClicks()),
			new FunnelStage("CONVERTED", kpi.couponUsed())));
	}
}
