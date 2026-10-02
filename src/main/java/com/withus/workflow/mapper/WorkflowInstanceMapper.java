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

	/** 노드 실행 오류(tx2 롤백 뒤 tx3): retry_count+1, 5분 뒤 재시도로 WAITING 되돌린다(워크플로우 Plan 5장) */
	void recordRetry(@Param("instanceId") long instanceId, @Param("nextRunAt") OffsetDateTime nextRunAt,
		@Param("errorMessage") String errorMessage);

	/** 재시도 3회 초과: FAILED 로 종단한다(워크플로우 Plan 5장) */
	void recordFailed(@Param("instanceId") long instanceId, @Param("errorMessage") String errorMessage);

	/**
	 * RUNNING 으로 10분 넘게 남은 건을 WAITING(next_run_at=now) 으로 되돌려 같은 단계부터 다시 처리하게
	 * 한다(DB_SCHEMA 7장, 워크플로우 Plan 5장). SEND 는 uq_send_log_step 이 중복 적재를 막아 멱등하다.
	 * @return 복구된 건수
	 */
	int recoverStuckRunning();

	/**
	 * 트리거 일괄 생성: customerIds 마다 인스턴스를 WAITING(next_run_at=now) 으로 만든다.
	 * uq_workflow_instance(campaign_id, customer_id) 충돌은 건너뛰어 재실행해도 멱등하다(워크플로우 Plan 6.1).
	 * @return 실제로 insert 된 건수
	 */
	int insertBatch(@Param("campaignId") long campaignId, @Param("stepId") long stepId,
		@Param("customerIds") List<Long> customerIds);
}
