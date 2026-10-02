package com.withus.workflow.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.withus.campaign.domain.SendLog;
import com.withus.campaign.mapper.SendLogMapper;
import com.withus.workflow.mapper.WorkflowInstanceMapper;

/**
 * RUNNING 으로 10분 넘게 남은 인스턴스를 WAITING(next_run_at=now) 으로 되돌려 같은 단계부터 다시
 * 처리하게 한다(DB_SCHEMA 7장, workflow-plan.md 5장) — 발송 큐의 SendRecoveryJob 과 같은 구조.
 */
@Component
public class WorkflowRecoveryJob {

	private static final Logger log = LoggerFactory.getLogger(WorkflowRecoveryJob.class);

	private final WorkflowInstanceMapper workflowInstanceMapper;
	private final SendLogMapper sendLogMapper;
	private final WorkflowWakeup workflowWakeup;
	private final boolean schedulerEnabled;

	public WorkflowRecoveryJob(WorkflowInstanceMapper workflowInstanceMapper, SendLogMapper sendLogMapper,
			WorkflowWakeup workflowWakeup,
			@Value("${withus.scheduler.workflow-recovery.enabled:true}") boolean schedulerEnabled) {
		this.workflowInstanceMapper = workflowInstanceMapper;
		this.sendLogMapper = sendLogMapper;
		this.workflowWakeup = workflowWakeup;
		this.schedulerEnabled = schedulerEnabled;
	}

	/** withus.scheduler.workflow-recovery.enabled=false 로 테스트에서 끌 수 있다(SendDispatcher 와 같은 이유) */
	@Scheduled(fixedDelay = 60_000)
	void scheduledRecover() {
		if (schedulerEnabled) {
			recover();
		}
	}

	/** @return 복구된(WAITING 으로 바뀐) 건수 */
	public int recover() {
		int recovered = workflowInstanceMapper.recoverStuckRunning();
		wakeOrphans();
		return recovered;
	}

	/**
	 * 발송 결과를 기다리는데(WAITING·next_run_at NULL) 그 발송 건은 이미 끝난 인스턴스를 깨운다. 쿠폰 기간 초과 SKIPPED,
	 * 깨우기 실패 등 wake() 가 빠진 경로의 마지막 안전망이다(PR #35 리뷰 🔴1). wake() 는 R1 조건과 next_run_at IS NULL 을
	 * 다시 확인하므로 여러 번 불러도 안전하다
	 * @return 깨우기를 시도한 건수
	 */
	public int wakeOrphans() {
		int tried = 0;
		for (SendLog sendLog : sendLogMapper.findTerminalSendsOfWaitingInstances()) {
			try {
				workflowWakeup.wake(sendLog);
				tried++;
			} catch (Exception e) {
				log.error("멈춘 인스턴스 깨우기 실패 sendLogId={}", sendLog.getSendLogId(), e);
			}
		}
		return tried;
	}
}
