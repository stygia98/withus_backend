package com.withus.tracking.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.withus.common.exception.BusinessException;
import com.withus.common.exception.CommonErrorCode;
import com.withus.tracking.domain.QueueCounts;
import com.withus.tracking.domain.RecentEventRow;
import com.withus.tracking.dto.CampaignAnalyticsResponse;
import com.withus.tracking.dto.DailySendResponse;
import com.withus.tracking.dto.DashboardSummaryResponse;
import com.withus.tracking.dto.QueueStatusResponse;
import com.withus.tracking.dto.RecentEventsResponse;
import com.withus.tracking.dto.SendKpi;
import com.withus.tracking.mapper.DashboardMapper;

/**
 * 메인 대시보드·캠페인 성과 집계 (PRD F-09, API_SPEC 10장).
 * 모든 지표는 kind = 'CAMPAIGN', 사람 이벤트(bot_yn = 'N')만 센다. 날짜는 한국 시간 기준.
 */
@Service
@Transactional(readOnly = true)
public class DashboardService {

	static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	static final int DEFAULT_SUMMARY_DAYS = 7;
	static final int MAX_SUMMARY_DAYS = 366;
	static final int MAX_DAILY_DAYS = 90;
	static final int MAX_EVENTS = 100;

	private final DashboardMapper dashboardMapper;
	private final double maxSendRate;
	private final LocalTime sendWindowStart;
	private final LocalTime sendWindowEnd;
	private Clock clock = Clock.system(SEOUL);

	public DashboardService(DashboardMapper dashboardMapper, @Value("${ses.max-send-rate}") double maxSendRate,
		@Value("${withus.send-window.start}") LocalTime sendWindowStart,
		@Value("${withus.send-window.end}") LocalTime sendWindowEnd) {
		this.dashboardMapper = dashboardMapper;
		this.maxSendRate = maxSendRate;
		this.sendWindowStart = sendWindowStart;
		this.sendWindowEnd = sendWindowEnd;
	}

	/** 테스트에서 현재 시각을 고정할 때만 쓴다 */
	void setClock(Clock clock) {
		this.clock = clock;
	}

	/** 기간 KPI. 기본은 오늘 포함 최근 7일. from·to 는 양 끝 포함, 최대 366일 */
	public DashboardSummaryResponse summary(LocalDate from, LocalDate to) {
		LocalDate end = to != null ? to : LocalDate.now(clock);
		LocalDate start = from != null ? from : end.minusDays(DEFAULT_SUMMARY_DAYS - 1);
		if (start.isAfter(end)) {
			throw invalid("from 은 to 보다 늦을 수 없습니다.");
		}
		if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_SUMMARY_DAYS) {
			throw invalid("조회 기간은 최대 " + MAX_SUMMARY_DAYS + "일입니다.");
		}
		OffsetDateTime fromTs = start.atStartOfDay(SEOUL).toOffsetDateTime();
		OffsetDateTime toTs = end.plusDays(1).atStartOfDay(SEOUL).toOffsetDateTime();
		return new DashboardSummaryResponse(start, end, SendKpi.of(dashboardMapper.sendStats(null, fromTs, toTs)));
	}

	/** 오늘 포함 최근 days 일의 일별 발송 성공 건수 (오래된 날짜부터, 발송 없는 날은 0) */
	public List<DailySendResponse> dailySends(int days) {
		if (days < 1 || days > MAX_DAILY_DAYS) {
			throw invalid("days 는 1~" + MAX_DAILY_DAYS + " 사이여야 합니다.");
		}
		LocalDate today = LocalDate.now(clock);
		return dashboardMapper.dailySends(today.minusDays(days - 1), today).stream()
			.map(row -> new DailySendResponse(row.getDate(), row.getSent()))
			.toList();
	}

	/** 발송 큐 현황과 예상 종료 시각 (큐는 운영 상태라 TEST·NOTICE 도 포함) */
	public QueueStatusResponse queue() {
		QueueCounts counts = dashboardMapper.queueCounts();
		OffsetDateTime now = OffsetDateTime.now(clock);
		long remaining = counts.getPending() + counts.getRetrying() + counts.getSending();
		OffsetDateTime expectedEndAt = remaining == 0 || maxSendRate <= 0 ? null
			: now.plusSeconds((long) Math.ceil(remaining / maxSendRate));
		return new QueueStatusResponse(counts.getPending(), counts.getSending(), counts.getRetrying(), maxSendRate,
			expectedEndAt, isAdSendWindowOpen(now.atZoneSameInstant(SEOUL).toLocalTime()));
	}

	/** 광고성 발송 가능 시간인가: 시작 포함, 종료 미포함 (08:00 ≤ t < 20:50) */
	boolean isAdSendWindowOpen(LocalTime time) {
		return !time.isBefore(sendWindowStart) && time.isBefore(sendWindowEnd);
	}

	/** 최근 이벤트 (10초 폴링). after 보다 새 이벤트를 최신순으로 최대 size 개 */
	public RecentEventsResponse events(Long after, int size) {
		if (size < 1 || size > MAX_EVENTS) {
			throw invalid("size 는 1~" + MAX_EVENTS + " 사이여야 합니다.");
		}
		List<RecentEventRow> events = dashboardMapper.recentEvents(after, size);
		Long lastEventId = events.isEmpty() ? after : events.get(0).getEventId();
		return new RecentEventsResponse(events, lastEventId);
	}

	/** 캠페인 KPI·전환 흐름 (기간 제한 없이 캠페인 전체) */
	public CampaignAnalyticsResponse campaign(long campaignId) {
		String name = dashboardMapper.campaignName(campaignId);
		if (name == null) {
			throw new BusinessException(CommonErrorCode.COMMON_NOT_FOUND);
		}
		return CampaignAnalyticsResponse.of(campaignId, name,
			SendKpi.of(dashboardMapper.sendStats(campaignId, null, null)));
	}

	private static BusinessException invalid(String message) {
		return new BusinessException(CommonErrorCode.COMMON_INVALID_INPUT, message, null);
	}
}
