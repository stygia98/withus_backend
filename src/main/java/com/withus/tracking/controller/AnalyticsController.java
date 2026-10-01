package com.withus.tracking.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.withus.common.response.ApiResponse;
import com.withus.tracking.dto.CampaignAnalyticsResponse;
import com.withus.tracking.dto.CampaignStepsResponse;
import com.withus.tracking.service.DashboardService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "성과 리포트", description = "캠페인 성과 (API_SPEC 10장). 모든 지표는 kind=CAMPAIGN, 봇 제외")
@RestController
@RequestMapping("/api/v1/analytics")
@PreAuthorize("hasAnyRole('OWNER','MANAGER','STAFF')")
@RequiredArgsConstructor
public class AnalyticsController {

	private final DashboardService dashboardService;

	@Operation(summary = "캠페인 KPI·전환 흐름", description = "캠페인 전체 기간의 발송 시도·성공, 고유 오픈·클릭·쿠폰 사용 고객과 비율, "
		+ "ATTEMPTED→SENT→OPENED→CLICKED→CONVERTED 흐름. 오류: COMMON_NOT_FOUND(404)")
	@GetMapping("/campaigns/{campaignId}")
	public ApiResponse<CampaignAnalyticsResponse> campaign(@PathVariable long campaignId) {
		return ApiResponse.ok(dashboardService.campaign(campaignId));
	}

	@Operation(summary = "워크플로우 단계별 집계", description = "SEND_EMAIL·SEND_SMS 단계마다 캠페인 KPI 와 같은 정의의 지표. "
		+ "일회성 캠페인이면 steps 는 빈 배열. 오류: COMMON_NOT_FOUND(404)")
	@GetMapping("/campaigns/{campaignId}/steps")
	public ApiResponse<CampaignStepsResponse> steps(@PathVariable long campaignId) {
		return ApiResponse.ok(dashboardService.steps(campaignId));
	}
}
