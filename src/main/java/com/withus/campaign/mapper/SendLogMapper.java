package com.withus.campaign.mapper;

import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.withus.campaign.domain.CustomerPlaceholderSource;
import com.withus.campaign.domain.CustomerRecipient;
import com.withus.campaign.domain.SendLog;
import com.withus.campaign.domain.SendStatus;
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

	/** 발송 시간창(08:00~20:50) 밖 보류: attempt_count 는 올리지 않고 next_attempt_at 만 설정한다(발송 큐 Plan 8장) */
	void holdForSendWindow(@Param("sendLogId") long sendLogId, @Param("nextAttemptAt") OffsetDateTime nextAttemptAt);

	/** 일시 오류(TRANSIENT) 재시도: attempt_count+1, next_attempt_at 설정, PENDING 으로 되돌린다(발송 큐 Plan 8장) */
	void recordRetry(@Param("sendLogId") long sendLogId, @Param("nextAttemptAt") OffsetDateTime nextAttemptAt,
		@Param("errorMessage") String errorMessage);

	/**
	 * SENDING 으로 10분 넘게 남은 건을 FAILED(UNKNOWN_RESULT) 로 되돌린다(재발송하지 않음, DB_SCHEMA 7장).
	 * @return 복구된 건수
	 */
	int recoverStuckSending();

	/** 렌더링용 치환 값 원본(customer SELECT) — MessageComposer 가 PlaceholderRenderer 에 넘길 Map 을 만든다 */
	CustomerPlaceholderSource findPlaceholderSource(@Param("customerId") long customerId);

	/** 일회성 캠페인에 연결된 쿠폰 ID (없으면 null) */
	Long findCouponIdByCampaignId(@Param("campaignId") long campaignId);

	/** 워크플로우 SEND 노드에 연결된 쿠폰 ID — config_json.couponId (없으면 null) */
	Long findCouponIdByStepId(@Param("stepId") long stepId);

	/** 오늘(Asia/Seoul)이 쿠폰 유효기간(valid_from~valid_to) 안인가 */
	boolean isCouponValid(@Param("couponId") long couponId);

	/** 재확인 이후 렌더링 단계에서 탈락(쿠폰 유효기간 밖): 종단 SKIPPED(COUPON_INVALID), 재시도하지 않는다 */
	void recordSkippedCoupon(@Param("sendLogId") long sendLogId);

	/** 전체 PENDING 대기 건수 — 새 캠페인 시작 전 예상 소요 시간 계산용(캠페인 3/4 estimate) */
	long countPending();

	/**
	 * 워크플로우 엔진의 WAIT 처리용(workflow-plan.md 3장) — 방금 enqueueWorkflowStep 으로 적재한 건의
	 * 상태(PENDING/SKIPPED)를 읽어온다. 해당 채널에 수신처가 없어 아예 적재되지 않았으면 null.
	 */
	SendStatus findStatusByInstanceStep(@Param("instanceId") long instanceId, @Param("stepId") long stepId);

	/**
	 * 워크플로우 CONDITION(EMAIL_OPENED/CLICKED)용 — 이 인스턴스의 가장 최근 EMAIL 발송 건
	 * (PRD 6.3 "직전 메일", workflow-plan.md 3.2 PL 리뷰 R3: 채널을 지정해야 SMS 가 섞인 구조에서
	 * 엉뚱한 건을 집지 않는다). 아직 메일을 보낸 적이 없으면 null
	 */
	Long findLatestSendLogId(@Param("instanceId") long instanceId, @Param("channel") Channel channel);
}
