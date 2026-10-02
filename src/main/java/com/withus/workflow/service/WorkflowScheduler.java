package com.withus.workflow.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.withus.workflow.domain.WorkflowInstance;
import com.withus.workflow.mapper.WorkflowInstanceMapper;

/**
 * 워크플로우 엔진 스케줄러 (PRD 6.5, 엔진 1/4) — 1분마다 실행 시각이 된 인스턴스를 선점해 처리할 건이
 * 없을 때까지 반복한다(10만 건이 한꺼번에 시작돼도 한 실행 주기 안에서 다 소진). 노드 실행은 엔진 2/4에서
 * 이 dispatch() 안에 끼워 넣는다 — 지금은 선점(WAITING→RUNNING)까지만 한다.
 */
@Component
public class WorkflowScheduler {

	private final WorkflowInstanceMapper workflowInstanceMapper;
	private final boolean schedulerEnabled;

	public WorkflowScheduler(WorkflowInstanceMapper workflowInstanceMapper,
			@Value("${withus.scheduler.workflow-engine.enabled:true}") boolean schedulerEnabled) {
		this.workflowInstanceMapper = workflowInstanceMapper;
		this.schedulerEnabled = schedulerEnabled;
	}

	/** withus.scheduler.workflow-engine.enabled=false 로 테스트에서 끌 수 있다(SendDispatcher 와 같은 이유) */
	@Scheduled(fixedDelay = 60_000)
	void scheduledDispatch() {
		if (schedulerEnabled) {
			dispatch();
		}
	}

	public void dispatch() {
		while (true) {
			List<WorkflowInstance> claimed = workflowInstanceMapper.claimBatch();
			if (claimed.isEmpty()) {
				return;
			}
			// 노드 실행은 엔진 2/4에서 여기에 추가한다
		}
	}
}
