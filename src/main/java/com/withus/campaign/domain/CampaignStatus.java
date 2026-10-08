package com.withus.campaign.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 캠페인 상태와 허용 전이표 (PRD 6.7). W3 상태 전이 API(pause·resume·complete 등)와
 * 발송 큐(선점 쿼리의 PAUSED 제외)가 이 표를 공유한다.
 */
public enum CampaignStatus {
	DRAFT, SCHEDULED, ACTIVE, PAUSED, COMPLETED;

	private static final Map<CampaignStatus, Set<CampaignStatus>> ALLOWED = new EnumMap<>(CampaignStatus.class);

	static {
		ALLOWED.put(DRAFT, EnumSet.of(SCHEDULED, ACTIVE));
		ALLOWED.put(SCHEDULED, EnumSet.of(ACTIVE, DRAFT));
		ALLOWED.put(ACTIVE, EnumSet.of(PAUSED, COMPLETED));
		ALLOWED.put(PAUSED, EnumSet.of(ACTIVE, COMPLETED));
		ALLOWED.put(COMPLETED, EnumSet.noneOf(CampaignStatus.class));
	}

	public static boolean canTransition(CampaignStatus from, CampaignStatus to) {
		return ALLOWED.get(from).contains(to);
	}
}
