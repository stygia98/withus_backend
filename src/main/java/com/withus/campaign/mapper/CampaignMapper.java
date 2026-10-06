package com.withus.campaign.mapper;

import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.withus.campaign.domain.Campaign;
import com.withus.campaign.domain.CampaignStatus;
import com.withus.campaign.domain.CampaignType;

@Mapper
public interface CampaignMapper {

	/** type·status 가 null 이면 해당 조건 제외 */
	List<Campaign> findList(@Param("type") CampaignType type, @Param("status") CampaignStatus status,
			@Param("offset") int offset, @Param("limit") int limit);

	long count(@Param("type") CampaignType type, @Param("status") CampaignStatus status);

	Campaign findById(long campaignId);

	/** 신규 가입 트리거(CUSTOMER_REGISTERED) 워크플로우 중 실행 중(ACTIVE)인 캠페인 */
	List<Campaign> findActiveCustomerRegistered();

	void insert(Campaign campaign);

	/** DRAFT 일 때만 바뀐다(수정 요청이 시작 요청과 겹쳐도 실행 중인 캠페인의 참조가 바뀌지 않게, PR #31 리뷰). @return 바뀐 행 수 */
	int update(Campaign campaign);

	/** 세그먼트 존재 확인(segment 테이블 SELECT) — 없는 ID 가 FK 오류(500)로 새는 걸 막는다 */
	boolean existsSegment(@Param("segmentId") long segmentId);

	/** 쿠폰 존재 확인(coupon 테이블 SELECT) — 없는 ID 가 FK 오류(500)로 새는 걸 막는다 */
	boolean existsCoupon(@Param("couponId") long couponId);

	/**
	 * 상태 변경(낙관적 검사): 현재 상태가 expectedStatus 와 같을 때만 바뀐다.
	 * @return 바뀐 행 수 — 0 이면 그 사이 다른 요청이 상태를 바꿨다는 뜻(CAMPAIGN_INVALID_STATUS)
	 */
	int updateStatus(@Param("campaignId") long campaignId, @Param("expectedStatus") CampaignStatus expectedStatus,
			@Param("newStatus") CampaignStatus newStatus);

	/** DRAFT → SCHEDULED, scheduled_at 설정(낙관적 검사). @return 바뀐 행 수 */
	int schedule(@Param("campaignId") long campaignId, @Param("scheduledAt") OffsetDateTime scheduledAt);

	/** SCHEDULED → DRAFT, scheduled_at 해제(낙관적 검사). @return 바뀐 행 수 */
	int cancelSchedule(long campaignId);

	/**
	 * 시작 선점(이슈 #52, PR #54 리뷰): DRAFT·SCHEDULED 이고 updated_at 이 readAt 이며 아무도 선점하지 않았을 때만(10분 넘은 낡은
	 * 선점은 무시) start_claimed_at 을 찍고 그 값을 돌려준다. updated_at 은 바꾸지 않으므로 선점 뒤에 읽어 온 다른 요청도
	 * 같은 readAt 으로 시도하지만 이미 선점돼 있어 null 이 된다. 선점하지 못하면 null
	 */
	OffsetDateTime claimStart(@Param("campaignId") long campaignId, @Param("readAt") OffsetDateTime readAt);

	/** 내 선점(claimedAt)을 푼다. 시작이 실패하거나 중단됐을 때 부른다. @return 풀린 행 수 */
	int releaseStart(@Param("campaignId") long campaignId, @Param("claimedAt") OffsetDateTime claimedAt);

	/**
	 * DRAFT·SCHEDULED → ACTIVE, started_at 설정, 선점 해제(낙관적 검사). 내 선점이 유지되고 선점 뒤 수정·예약 취소로
	 * updated_at 이 바뀌지 않았을 때만 바뀐다. @return 바뀐 행 수
	 */
	int start(@Param("campaignId") long campaignId, @Param("readAt") OffsetDateTime readAt,
		@Param("claimedAt") OffsetDateTime claimedAt);

	/** 예약 시각이 지난 SCHEDULED 캠페인 ID 목록(캠페인 4/4) */
	List<Long> findDueScheduled();

	/**
	 * 자동 완료 대상: 일회성이고 ACTIVE 로 시작까지 마쳤는데(started_at 있음) PENDING·SENDING 이 하나도 없는 캠페인.
	 * 적재가 ACTIVE 전환보다 먼저라(CampaignService.start) 시작 직후 "남은 건 없음"으로 잘못 집히지 않는다(PR #31 리뷰 🔴1)
	 */
	List<Long> findCompletable();

	/**
	 * 워크플로우 자동 완료 대상: SEGMENT_SCHEDULED 이고 ACTIVE 인데 인스턴스가 있으며 WAITING·RUNNING 이 없는 캠페인
	 * (COMPLETED·FAILED·CANCELLED 만 남음 — FAILED 도 끝난 것으로 본다, 안 그러면 한 건 실패로 영원히 ACTIVE).
	 * 인스턴스가 하나도 없으면(시작 직후 생성 전, 빈 세그먼트) 대상이 아니다
	 */
	List<Long> findCompletableWorkflow();

	/** ACTIVE → COMPLETED, ended_at 설정(낙관적 검사). @return 바뀐 행 수 */
	int complete(long campaignId);

	/** 수동 종료: ACTIVE·PAUSED → COMPLETED, ended_at 설정(PRD 6.7 전이표, 낙관적 검사). @return 바뀐 행 수 */
	int completeManually(long campaignId);
}
