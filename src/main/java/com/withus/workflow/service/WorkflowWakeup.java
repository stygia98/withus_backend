package com.withus.workflow.service;

import com.withus.campaign.domain.SendLog;

/** 발송 결과 ↔ 워크플로우 연결 (워크플로우 Plan 4장, PRD 10.1 구간 간 연결 지점). SendDispatcher 가 결과 기록 트랜잭션 안에서 부른다 */
public interface WorkflowWakeup {

	/** sendLog 가 워크플로우 발송 건(instanceId != null)이면 기다리던 인스턴스의 다음 실행 시각을 정한다. 아니면 아무 일도 없다 */
	void wake(SendLog sendLog);
}
