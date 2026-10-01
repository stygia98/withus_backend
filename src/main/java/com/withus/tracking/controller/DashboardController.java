package com.withus.tracking.controller;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.withus.common.response.ApiResponse;
import com.withus.tracking.dto.DailySendResponse;
import com.withus.tracking.dto.DashboardSummaryResponse;
import com.withus.tracking.dto.QueueStatusResponse;
import com.withus.tracking.dto.RecentEventsResponse;
import com.withus.tracking.service.DashboardService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "대시보드", description = "메인 대시보드 (API_SPEC 10장). 모든 지표는 kind=CAMPAIGN, 봇 제외")
@RestController
@RequestMapping("/api/v1/dashboard")
@PreAuthorize("hasAnyRole('OWNER','MANAGER','STAFF')")
@RequiredArgsConstructor
public class DashboardController {

	private final DashboardService dashboardService;

	@Operation(summary = "기간 KPI", description = "총 발송 시도·성공, 성공률, 오픈율·클릭률·전환율(고유 고객 / 발송 성공). "
		+ "from·to 는 한국 시간 날짜(YYYY-MM-DD), 양 끝 포함, 최대 366일. 생략하면 오늘 포함 최근 7일. "
		+ "오류: COMMON_INVALID_INPUT(400)")
	@GetMapping("/summary")
	public ApiResponse<DashboardSummaryResponse> summary(
		@Parameter(example = "2026-09-25") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
		@Parameter(example = "2026-10-01") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return ApiResponse.ok(dashboardService.summary(from, to));
	}

	@Operation(summary = "일별 발송", description = "오늘 포함 최근 days 일의 날짜별 발송 성공 건수. 오래된 날짜부터, 발송 없는 날은 0. "
		+ "days 1~90 (기본 14)")
	@GetMapping("/daily-sends")
	public ApiResponse<List<DailySendResponse>> dailySends(@RequestParam(defaultValue = "14") int days) {
		return ApiResponse.ok(dashboardService.dailySends(days));
	}

	@Operation(summary = "발송 큐 상태", description = "대기·발송 중·재시도 대기 건수, 초당 발송 한도, 예상 종료 시각, "
		+ "지금이 광고성 발송 가능 시간(08:00~20:50)인지. 큐는 운영 상태라 TEST·NOTICE 도 포함")
	@GetMapping("/queue")
	public ApiResponse<QueueStatusResponse> queue() {
		return ApiResponse.ok(dashboardService.queue());
	}

	@Operation(summary = "최근 이벤트", description = "오픈·클릭 이벤트 최신순(봇·TEST·NOTICE 제외). 10초 폴링 시 직전 응답의 "
		+ "lastEventId 를 after 로 넘기면 그보다 새 이벤트만 온다. size 1~100 (기본 20)")
	@GetMapping("/events")
	public ApiResponse<RecentEventsResponse> events(@RequestParam(required = false) Long after,
		@RequestParam(defaultValue = "20") int size) {
		return ApiResponse.ok(dashboardService.events(after, size));
	}
}
