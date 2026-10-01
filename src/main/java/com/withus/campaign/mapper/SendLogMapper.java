package com.withus.campaign.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.withus.campaign.domain.CustomerRecipient;
import com.withus.campaign.domain.SendLog;
import com.withus.common.domain.Channel;

@Mapper
public interface SendLogMapper {

	/** 적재 시점 수신처 조회(customer 테이블 SELECT, 삭제된 고객 제외). channel 에 따라 email 또는 phone */
	List<CustomerRecipient> findRecipients(@Param("customerIds") List<Long> customerIds, @Param("channel") Channel channel);

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

	/**
	 * 선점: PENDING 중 priority·send_log_id 순으로 최대 50건을 SENDING 으로 바꾸고 그 행을 돌려준다.
	 * PAUSED 캠페인 건은 선점 대상에서 제외한다(발송 큐 Plan 15장 A1 — 안 하면 계속 선점·반환을 반복해 다른 캠페인이 밀린다).
	 */
	List<SendLog> claimBatch();

	/** 발송 성공 기록 */
	void recordSent(@Param("sendLogId") long sendLogId, @Param("providerMessageId") String providerMessageId);

	/** 발송 실패(영구 오류) 기록 */
	void recordFailed(@Param("sendLogId") long sendLogId, @Param("errorMessage") String errorMessage);

	/** SES 웹훅용 — provider_message_id 로 send_log(고객 포함) 를 찾는다 (팀원1) */
	SendLog findByProviderMessageId(@Param("providerMessageId") String providerMessageId);

	/** 발송 직전 재확인(SendRecheck)용 — 선점 당시와 캠페인 상태가 바뀌었는지 다시 본다 */
	String findCampaignStatus(@Param("campaignId") long campaignId);

	/** 재확인 탈락(고객 삭제·동의 N·suppression·캠페인 COMPLETED): 종단 SKIPPED, 재시도하지 않는다 */
	void recordSkipped(@Param("sendLogId") long sendLogId);

	/** 재확인 보류(캠페인 PAUSED): next_attempt_at 은 바꾸지 않고 PENDING 으로 되돌린다(발송 큐 Plan 8장) */
	void revertToPending(@Param("sendLogId") long sendLogId);
}
