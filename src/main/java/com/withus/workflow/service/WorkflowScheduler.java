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
					// processOne 은 tx2 실패를 내부에서 잡아 tx3(재시도/FAILED)로 기록하므로 보통 던지지 않는다.
					// 이 catch 는 tx3 자체가 실패하는 등 마지막 안전망이다(발송 큐 PR #21 리뷰와 같은 교훈 —
					// 한 건의 예외가 배치 전체를 멈추면 안 된다). 그래도 멈추면 RUNNING에 남아 멈춤 복구가 되돌린다
					workflowEngine.processOne(instance);
				} catch (Exception e) {
					log.error("워크플로우 인스턴스 처리 중 예상치 못한 오류 instanceId={}", instance.getInstanceId(), e);
				}
			}
		}
	}
}
