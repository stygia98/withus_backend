package com.withus.campaign.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.withus.campaign.mapper.SendLogMapper;

/**
 * SENDING 으로 10분 넘게 남은 건을 FAILED(UNKNOWN_RESULT) 로 되돌린다 — 재발송하지 않는다,
 * 중복 발송보다 누락이 낫다(발송 큐 Plan 10장, DB_SCHEMA 7장).
 */
@Component
public class SendRecoveryJob {

	private final SendLogMapper sendLogMapper;
	private final boolean schedulerEnabled;

	public SendRecoveryJob(SendLogMapper sendLogMapper,
			@Value("${withus.scheduler.send-recovery.enabled:true}") boolean schedulerEnabled) {
		this.sendLogMapper = sendLogMapper;
		this.schedulerEnabled = schedulerEnabled;
	}

	/** withus.scheduler.send-recovery.enabled=false 로 테스트에서 끌 수 있다(SendDispatcher 와 같은 이유) */
	@Scheduled(fixedDelay = 60_000)
	void scheduledRecover() {
		if (schedulerEnabled) {
			recover();
		}
	}

	/** @return 복구된(FAILED 로 바뀐) 건수 */
	public int recover() {
		return sendLogMapper.recoverStuckSending();
	}
}
