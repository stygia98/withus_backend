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

	/** 규칙에 맞는 삭제되지 않은 고객 ID (customer_id 순) */
	List<Long> findTargetCustomerIds(@Param("q") SegmentQuery query);

	long countTargets(@Param("q") SegmentQuery query);

	SegmentPreviewResponse preview(@Param("q") SegmentQuery query);
}
