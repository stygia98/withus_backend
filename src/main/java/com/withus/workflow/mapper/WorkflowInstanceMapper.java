package com.withus.workflow.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;

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
}
