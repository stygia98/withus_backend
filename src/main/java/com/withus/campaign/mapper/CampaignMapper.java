package com.withus.campaign.mapper;

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
}
