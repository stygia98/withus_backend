package com.withus.workflow.mapper;

import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.withus.workflow.domain.WorkflowInstance;

@Mapper
public interface WorkflowInstanceMapper {

	/**
	 * 선점: WAITING 중 next_run_at 이 지난 것을 최대 500건 RUNNING 으로 바꾸고 그 행을 돌려준다
	 * (DB_SCHEMA 7장). 캠페인이 ACTIVE 인 것만 선점한다(PAUSED·COMPLETED 등은 제외) — 발송 큐
	 * claimBatch 의 PAUSED 제외(Plan 15장 A1)와 같은 이유로, 안 하면 멈춘 캠페인 건이 계속
	 * 선점·방치를 반복해 다른 캠페인이 밀린다.
	 */
	List<WorkflowInstance> claimBatch();

	/** WAIT 도달, 직전 SEND 가 PENDING(발송 큐로 들어감): next_run_at 을 비워 wake() 를 기다린다(워크플로우 Plan 3.1) */
	void moveToWaitPending(@Param("instanceId") long instanceId, @Param("nextStepId") long nextStepId);

	/** WAIT 도달, 그 외 경우(직전 SEND 가 SKIPPED 였거나 SEND 가 아니었음): 대기 시각을 바로 계산해 기록한다 */
	void moveToWait(@Param("instanceId") long instanceId, @Param("nextStepId") long nextStepId,
		@Param("nextRunAt") OffsetDateTime nextRunAt);

	/** END 도달: current_step_id 는 그대로 두고 상태만 COMPLETED 로 바꾼다(워크플로우 Plan 3장) */
	void complete(@Param("instanceId") long instanceId);
}
