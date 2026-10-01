package com.withus.ai.mapper;

import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.withus.ai.domain.HourlyEngagement;

/** AI-02 집계 (track_event·send_log 는 다른 도메인 테이블이라 SELECT 만) */
@Mapper
public interface SendTimeMapper {

	List<HourlyEngagement> engagementByDayHour(@Param("since") OffsetDateTime since);

	/** 지금 발송 큐에 쌓여 있는 PENDING 건수 (예상 소요 시간 계산용, PRD 5.3) */
	long countPending();
}
