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

	void update(Campaign campaign);

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

	/** DRAFT·SCHEDULED → ACTIVE, started_at 설정(낙관적 검사). @return 바뀐 행 수 */
	int start(long campaignId);

	/** 예약 시각이 지난 SCHEDULED 캠페인 ID 목록(캠페인 4/4) */
	List<Long> findDueScheduled();

	/**
	 * 자동 완료 대상: 일회성이고 ACTIVE 로 시작까지 마쳤는데(started_at 있음) PENDING·SENDING 이 하나도 없는 캠페인.
	 * started_at 조건으로 시작 직후(큐 적재 전) 찰나에 집히는 걸 막는다(캠페인 4/4)
	 */
	List<Long> findCompletable();

	/** ACTIVE → COMPLETED, ended_at 설정(낙관적 검사). @return 바뀐 행 수 */
	int complete(long campaignId);
}
