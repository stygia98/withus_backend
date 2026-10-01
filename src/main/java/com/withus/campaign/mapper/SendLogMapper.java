package com.withus.campaign.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.withus.campaign.domain.SendLog;

@Mapper
public interface SendLogMapper {

	/**
	 * 일회성·A/B 캠페인용 배치 적재. instanceId 는 전부 null 이어야 한다.
	 * uq_send_log_one_time(campaign_id, customer_id) WHERE instance_id IS NULL 충돌은 건너뛴다.
	 * @return 실제로 insert 된 건수 (충돌로 건너뛴 건 제외)
	 */
	int insertOneTimeBatch(@Param("logs") List<SendLog> logs);

	/**
	 * 워크플로우 SEND 노드용 배치 적재. instanceId·stepId 가 전부 있어야 한다.
	 * uq_send_log_step(instance_id, step_id) 충돌은 건너뛴다.
	 * @return 실제로 insert 된 건수 (충돌로 건너뛴 건 제외)
	 */
	int insertWorkflowBatch(@Param("logs") List<SendLog> logs);
}
