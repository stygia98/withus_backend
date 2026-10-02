package com.withus.workflow.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.withus.workflow.mapper.WorkflowInstanceMapper;

/**
 * RUNNING 으로 10분 넘게 남은 인스턴스를 WAITING(next_run_at=now) 으로 되돌려 같은 단계부터 다시
 * 처리하게 한다(DB_SCHEMA 7장, workflow-plan.md 5장) — 발송 큐의 SendRecoveryJob 과 같은 구조.
 */
@Component
public class WorkflowRecoveryJob {

	private final WorkflowInstanceMapper workflowInstanceMapper;
	private final boolean schedulerEnabled;

	public WorkflowRecoveryJob(WorkflowInstanceMapper workflowInstanceMapper,
			@Value("${withus.scheduler.workflow-recovery.enabled:true}") boolean schedulerEnabled) {
		this.workflowInstanceMapper = workflowInstanceMapper;
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
		return workflowInstanceMapper.recoverStuckRunning();
	}
}
