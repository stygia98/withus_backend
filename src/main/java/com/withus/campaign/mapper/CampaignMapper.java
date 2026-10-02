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
	 * 시작 선점(PR #31 재리뷰, 이슈 #52): DRAFT·SCHEDULED 이고 updated_at 이 expectedUpdatedAt 일 때만 updated_at 을 새로 찍고
	 * 그 값을 돌려준다. 이미 다른 요청이 선점했거나 상태가 바뀌었으면 null
	 */
	OffsetDateTime claimStart(@Param("campaignId") long campaignId,
		@Param("expectedUpdatedAt") OffsetDateTime expectedUpdatedAt);

	/**
	 * DRAFT·SCHEDULED → ACTIVE, started_at 설정(낙관적 검사). 선점(claimStart) 이후 수정·예약 취소가 끼어 updated_at 이 바뀌었으면
	 * 바뀌지 않는다. @return 바뀐 행 수
	 */
	int start(@Param("campaignId") long campaignId, @Param("claimedAt") OffsetDateTime claimedAt);

	/** 예약 시각이 지난 SCHEDULED 캠페인 ID 목록(캠페인 4/4) */
	List<Long> findDueScheduled();

	/**
	 * 자동 완료 대상: 일회성이고 ACTIVE 로 시작까지 마쳤는데(started_at 있음) PENDING·SENDING 이 하나도 없는 캠페인.
	 * 적재가 ACTIVE 전환보다 먼저라(CampaignService.start) 시작 직후 "남은 건 없음"으로 잘못 집히지 않는다(PR #31 리뷰 🔴1)
	 */
	List<Long> findCompletable();

	/** ACTIVE → COMPLETED, ended_at 설정(낙관적 검사). @return 바뀐 행 수 */
	int complete(long campaignId);
}
