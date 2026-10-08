package com.withus.campaign.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.withus.campaign.domain.SendLog;
import com.withus.campaign.mapper.SendLogMapper;
import com.withus.workflow.service.WorkflowWakeup;

/**
 * SENDING 으로 10분 넘게 남은 건을 FAILED(UNKNOWN_RESULT) 로 되돌린다 — 재발송하지 않는다,
 * 중복 발송보다 누락이 낫다(발송 큐 Plan 10장, DB_SCHEMA 7장).
 */
@Component
public class SendRecoveryJob {

	private static final Logger log = LoggerFactory.getLogger(SendRecoveryJob.class);

	private final SendLogMapper sendLogMapper;
	private final WorkflowWakeup workflowWakeup;
	private final boolean schedulerEnabled;

	public SendRecoveryJob(SendLogMapper sendLogMapper, WorkflowWakeup workflowWakeup,
			@Value("${withus.scheduler.send-recovery.enabled:true}") boolean schedulerEnabled) {
		this.sendLogMapper = sendLogMapper;
		this.workflowWakeup = workflowWakeup;
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
		List<SendLog> recovered = sendLogMapper.recoverStuckSending();
		// UNKNOWN_RESULT 로 끝난 워크플로우 발송 건을 기다리던 인스턴스를 깨운다 — 안 깨우면 next_run_at NULL 로 영원히 멈춘다
		for (SendLog sendLog : recovered) {
			try {
				workflowWakeup.wake(sendLog);
			} catch (Exception e) {
				log.error("복구한 발송 건의 워크플로우 깨우기 실패 sendLogId={}", sendLog.getSendLogId(), e);
			}
		}
		return recovered.size();
	}
}
