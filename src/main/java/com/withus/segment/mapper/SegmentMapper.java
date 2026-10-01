package com.withus.segment.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.withus.segment.domain.Segment;
import com.withus.segment.domain.SegmentQuery;
import com.withus.segment.dto.SegmentPreviewResponse;

@Mapper
public interface SegmentMapper {

	void insert(Segment segment);

	void insertRule(@Param("segmentId") long segmentId, @Param("ruleJson") String ruleJson);

	Segment findById(long segmentId);

	List<Segment> findPage(@Param("limit") int limit, @Param("offset") long offset);

	long count();

	/** 이름·설명. 없는 세그먼트면 0 */
	int update(@Param("segmentId") long segmentId, @Param("name") String name,
		@Param("description") String description);

	void updateRule(@Param("segmentId") long segmentId, @Param("ruleJson") String ruleJson);

	/** 상태와 관계없이 이 세그먼트를 쓰는 캠페인이 있는지 (campaign.segment_id 는 CASCADE 없는 FK) */
	boolean existsCampaign(long segmentId);

	/** 없는 세그먼트면 0 */
	int delete(long segmentId);

	/** 규칙에 맞는 삭제되지 않은 고객 ID (customer_id 순) */
	List<Long> findTargetCustomerIds(@Param("q") SegmentQuery query);

	long countTargets(@Param("q") SegmentQuery query);

	SegmentPreviewResponse preview(@Param("q") SegmentQuery query);
}
