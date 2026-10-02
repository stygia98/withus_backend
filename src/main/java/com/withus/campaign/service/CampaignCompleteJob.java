package com.withus.campaign.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.withus.campaign.mapper.CampaignMapper;

/**
 * PENDING·SENDING 이 하나도 남지 않은 일회성 캠페인을 COMPLETED 로 바꾼다(캠페인 4/4, PRD 6.7).
 * 워크플로우는 범위 밖이다 — 인스턴스가 전부 끝나야 완료되는 판정은 W3 트리거 작업이 맡는다.
 */
@Component
public class CampaignCompleteJob {

	private static final Logger log = LoggerFactory.getLogger(CampaignCompleteJob.class);

	private final CampaignMapper campaignMapper;
	private final boolean schedulerEnabled;

	public CampaignCompleteJob(CampaignMapper campaignMapper,
			@Value("${withus.scheduler.campaign-complete.enabled:true}") boolean schedulerEnabled) {
		this.campaignMapper = campaignMapper;
		this.schedulerEnabled = schedulerEnabled;
	}

	/** withus.scheduler.campaign-complete.enabled=false 로 테스트에서 끌 수 있다(SendDispatcher 와 같은 이유) */
	@Scheduled(fixedDelay = 60_000)
	void scheduledComplete() {
		if (schedulerEnabled) {
			completeFinished();
		}
	}

	/** @return COMPLETED 로 바뀐 건수 */
	public int completeFinished() {
		int completed = 0;
		for (long campaignId : campaignMapper.findCompletable()) {
			try {
				completed += campaignMapper.complete(campaignId);
			} catch (RuntimeException e) {
				// 한 건의 오류가 같은 주기의 나머지 캠페인 완료를 막지 않게 건별로 격리한다(PR #31 리뷰 🔵9)
				log.error("캠페인 자동 완료 실패 campaignId={}", campaignId, e);
			}
		}
		return completed;
	}
}
