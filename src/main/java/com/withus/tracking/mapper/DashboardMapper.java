package com.withus.tracking.mapper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.withus.tracking.domain.DailySendRow;
import com.withus.tracking.domain.QueueCounts;
import com.withus.tracking.domain.RecentEventRow;
import com.withus.tracking.domain.SendStats;

/** 대시보드·성과 집계 (send_log·campaign·customer 는 다른 도메인 테이블, SELECT 만) */
@Mapper
public interface DashboardMapper {

	/**
	 * 발송·반응 집계. campaignId 가 있으면 그 캠페인만, from/to 가 있으면 그 기간만 (to 는 미포함).
	 * 기간은 발송 시각(실패 건은 마지막 처리 시각) 기준
	 */
	SendStats sendStats(@Param("campaignId") Long campaignId, @Param("from") OffsetDateTime from,
		@Param("to") OffsetDateTime to);

	/** from~to(포함) 의 날짜별 발송 성공 건수. 한국 시간 날짜 기준 */
	List<DailySendRow> dailySends(@Param("from") LocalDate from, @Param("to") LocalDate to);

	QueueCounts queueCounts();

	/** after 보다 큰 이벤트를 최신순으로 limit 개. after 가 null 이면 최신 limit 개 */
	List<RecentEventRow> recentEvents(@Param("after") Long after, @Param("limit") int limit);

	/** 캠페인 이름. 없으면 null */
	String campaignName(@Param("campaignId") long campaignId);
}
