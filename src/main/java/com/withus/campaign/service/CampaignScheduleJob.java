package com.withus.campaign.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.withus.campaign.mapper.CampaignMapper;
import com.withus.common.exception.BusinessException;

/**
 * 예약 시각이 된 SCHEDULED 캠페인을 시작한다(캠페인 4/4, 발송 큐 Plan 10장 CampaignScheduleJob).
 * 실제 시작 로직(ACTIVE 전환·일회성 즉시 적재)은 수동 /start API 와 똑같이 CampaignService.start 를 쓴다
 * — 적재는 uq_send_log_one_time 로 멱등이라 재실행돼도 안전하다.
 */
@Component
public class CampaignScheduleJob {

	private final CampaignMapper campaignMapper;
	private final CampaignService campaignService;
	private final boolean schedulerEnabled;

	public CampaignScheduleJob(CampaignMapper campaignMapper, CampaignService campaignService,
			@Value("${withus.scheduler.campaign-schedule.enabled:true}") boolean schedulerEnabled) {
		this.campaignMapper = campaignMapper;
		this.campaignService = campaignService;
		this.schedulerEnabled = schedulerEnabled;
	}

	/** withus.scheduler.campaign-schedule.enabled=false 로 테스트에서 끌 수 있다(SendDispatcher 와 같은 이유) */
	@Scheduled(fixedDelay = 60_000)
	void scheduledActivate() {
		if (schedulerEnabled) {
			activateScheduled();
		}
	}

	/** @return 시작에 성공한 건수. 조건(창·쿠폰 기간)이 지금은 안 맞는 캠페인은 건너뛰고 다음 주기에 다시 본다 */
	public int activateScheduled() {
		int started = 0;
		for (long campaignId : campaignMapper.findDueScheduled()) {
			try {
				campaignService.start(campaignId);
				started++;
			} catch (BusinessException e) {
				// CAMPAIGN_SEND_WINDOW_EXCEEDED·COUPON_OUT_OF_PERIOD 등 — 다음 주기에 재시도
			}
		}
		return started;
	}
}
