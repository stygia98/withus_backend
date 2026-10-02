package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.withus.campaign.domain.CampaignStatus;

/** 캠페인 상태 전이표(PRD 6.7) — 허용·금지 쌍 전부(25개 조합) 검증 */
class CampaignStatusTest {

	// "from->to" 형식으로 허용된 쌍만 나열한다. 나머지 모든 조합(자기 자신 포함)은 금지여야 한다
	private static final Set<String> ALLOWED = Set.of(
		"DRAFT->SCHEDULED", "DRAFT->ACTIVE",
		"SCHEDULED->ACTIVE", "SCHEDULED->DRAFT",
		"ACTIVE->PAUSED", "ACTIVE->COMPLETED",
		"PAUSED->ACTIVE", "PAUSED->COMPLETED");

	@Test
	void 허용된_전이와_금지된_전이가_PRD_6_7_표와_정확히_일치한다() {
		for (CampaignStatus from : CampaignStatus.values()) {
			for (CampaignStatus to : CampaignStatus.values()) {
				boolean expected = ALLOWED.contains(from + "->" + to);
				assertThat(CampaignStatus.canTransition(from, to))
					.as("%s -> %s", from, to)
					.isEqualTo(expected);
			}
		}
	}

	@Test
	void COMPLETED는_어디로도_전이할_수_없다() {
		for (CampaignStatus to : CampaignStatus.values()) {
			assertThat(CampaignStatus.canTransition(CampaignStatus.COMPLETED, to)).isFalse();
		}
	}
}
