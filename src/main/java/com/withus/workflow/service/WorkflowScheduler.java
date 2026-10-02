package com.withus.workflow.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.withus.workflow.domain.WorkflowInstance;
import com.withus.workflow.mapper.WorkflowInstanceMapper;

/**
 * 워크플로우 엔진 스케줄러 (PRD 6.5, 엔진 1/4) — 1분마다 실행 시각이 된 인스턴스를 선점해 처리할 건이
 * 없을 때까지 반복한다(10만 건이 한꺼번에 시작돼도 한 실행 주기 안에서 다 소진).
 */
@Component
public class WorkflowScheduler {

	private static final Logger log = LoggerFactory.getLogger(WorkflowScheduler.class);

	private final WorkflowInstanceMapper workflowInstanceMapper;
	private final WorkflowEngine workflowEngine;
	private final boolean schedulerEnabled;

	public WorkflowScheduler(WorkflowInstanceMapper workflowInstanceMapper, WorkflowEngine workflowEngine,
			@Value("${withus.scheduler.workflow-engine.enabled:true}") boolean schedulerEnabled) {
		this.workflowInstanceMapper = workflowInstanceMapper;
		this.workflowEngine = workflowEngine;
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
			for (WorkflowInstance instance : claimed) {
				try {
					workflowEngine.processOne(instance);
				} catch (Exception e) {
					// 한 건의 예외가 배치 전체를 멈추면 안 된다(발송 큐 PR #21 리뷰와 같은 교훈).
					// tx2 가 롤백돼 RUNNING에 남으므로, 엔진 4/4의 멈춤 복구가 나중에 WAITING 으로 되돌린다
					log.warn("워크플로우 인스턴스 처리 실패 instanceId={}", instance.getInstanceId(), e);
				}
			}
		}
	}
}
