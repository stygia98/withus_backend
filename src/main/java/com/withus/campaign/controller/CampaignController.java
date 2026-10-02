package com.withus.campaign.controller;

import java.time.OffsetDateTime;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.withus.auth.security.AuthMember;
import com.withus.campaign.domain.Campaign;
import com.withus.campaign.domain.CampaignStatus;
import com.withus.campaign.domain.CampaignType;
import com.withus.campaign.dto.CampaignCreateRequest;
import com.withus.campaign.dto.CampaignEstimateResponse;
import com.withus.campaign.dto.CampaignResponse;
import com.withus.campaign.dto.CampaignScheduleRequest;
import com.withus.campaign.dto.CampaignUpdateRequest;
import com.withus.campaign.service.CampaignService;
import com.withus.common.response.ApiResponse;
import com.withus.common.response.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@Tag(name = "캠페인", description = "일회성·워크플로우 캠페인 CRUD (API_SPEC 6장)")
@RestController
@RequestMapping("/api/v1/campaigns")
public class CampaignController {

	private final CampaignService campaignService;

	public CampaignController(CampaignService campaignService) {
		this.campaignService = campaignService;
	}

	@Operation(summary = "캠페인 목록", description = "type·status 필터(생략 시 전체). page 는 0부터, size 기본 20.")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER','STAFF')")
	@GetMapping
	public ApiResponse<PageResponse<CampaignResponse>> list(
		@RequestParam(required = false) CampaignType type,
		@RequestParam(required = false) CampaignStatus status,
		@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "20") int size) {
		PageResponse<Campaign> result = campaignService.list(type, status, page, size);
		PageResponse<CampaignResponse> body = PageResponse.of(
			result.content().stream().map(CampaignResponse::from).toList(),
			result.page(), result.size(), result.totalElements());
		return ApiResponse.ok(body);
	}

	@Operation(summary = "캠페인 상세")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER','STAFF')")
	@GetMapping("/{campaignId}")
	public ApiResponse<CampaignResponse> detail(@PathVariable long campaignId) {
		return ApiResponse.ok(CampaignResponse.from(campaignService.getOrThrow(campaignId)));
	}

	@Operation(summary = "캠페인 생성", description = "항상 DRAFT 로 만들어진다. 오류: CAMPAIGN_COUPON_REQUIRED(422).")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
	@PostMapping
	public ApiResponse<CampaignResponse> create(@Valid @RequestBody CampaignCreateRequest request,
		@AuthenticationPrincipal AuthMember me) {
		Campaign campaign = campaignService.create(request.toCampaign(), me.memberId());
		return ApiResponse.ok(CampaignResponse.from(campaign));
	}

	@Operation(summary = "캠페인 수정", description = "DRAFT 상태만 수정할 수 있다. 그 외 상태면 CAMPAIGN_INVALID_STATUS(409).")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
	@PutMapping("/{campaignId}")
	public ApiResponse<CampaignResponse> update(@PathVariable long campaignId,
		@Valid @RequestBody CampaignUpdateRequest request) {
		Campaign campaign = campaignService.update(campaignId, request.toCampaign());
		return ApiResponse.ok(CampaignResponse.from(campaign));
	}

	@Operation(summary = "예상 소요 시간·발송 가능 여부", description = "일회성 캠페인만 지원. 20:50 을 넘기면 allowed=false.")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
	@GetMapping("/{campaignId}/estimate")
	public ApiResponse<CampaignEstimateResponse> estimate(@PathVariable long campaignId,
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startAt) {
		return ApiResponse.ok(campaignService.estimate(campaignId, startAt));
	}

	@Operation(summary = "캠페인 예약", description = "DRAFT → SCHEDULED. 오류: CAMPAIGN_SEND_WINDOW_EXCEEDED(422), COUPON_OUT_OF_PERIOD(422).")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
	@PostMapping("/{campaignId}/schedule")
	public ApiResponse<CampaignResponse> schedule(@PathVariable long campaignId,
		@Valid @RequestBody CampaignScheduleRequest request) {
		Campaign campaign = campaignService.schedule(campaignId, request.scheduledAt());
		return ApiResponse.ok(CampaignResponse.from(campaign));
	}

	@Operation(summary = "예약 취소", description = "SCHEDULED → DRAFT.")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
	@PostMapping("/{campaignId}/cancel-schedule")
	public ApiResponse<CampaignResponse> cancelSchedule(@PathVariable long campaignId) {
		Campaign campaign = campaignService.cancelSchedule(campaignId);
		return ApiResponse.ok(CampaignResponse.from(campaign));
	}

	@Operation(summary = "즉시 시작", description = "DRAFT·SCHEDULED → ACTIVE. 일회성은 바로 큐에 적재한다.")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
	@PostMapping("/{campaignId}/start")
	public ApiResponse<CampaignResponse> start(@PathVariable long campaignId) {
		Campaign campaign = campaignService.start(campaignId);
		return ApiResponse.ok(CampaignResponse.from(campaign));
	}

	@Operation(summary = "일시정지", description = "ACTIVE → PAUSED. 인스턴스 실행과 PENDING 발송이 멈춘다. 그 외 상태면 CAMPAIGN_INVALID_STATUS(409).")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
	@PostMapping("/{campaignId}/pause")
	public ApiResponse<CampaignResponse> pause(@PathVariable long campaignId) {
		return ApiResponse.ok(CampaignResponse.from(campaignService.pause(campaignId)));
	}

	@Operation(summary = "재개", description = "PAUSED → ACTIVE. 그 외 상태면 CAMPAIGN_INVALID_STATUS(409).")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
	@PostMapping("/{campaignId}/resume")
	public ApiResponse<CampaignResponse> resume(@PathVariable long campaignId) {
		return ApiResponse.ok(CampaignResponse.from(campaignService.resume(campaignId)));
	}

	@Operation(summary = "복제", description = "새 DRAFT 로 복제한다. 워크플로우는 구조(노드)까지 복사하고 인스턴스·발송 이력은 복사하지 않는다.")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
	@PostMapping("/{campaignId}/duplicate")
	public ApiResponse<CampaignResponse> duplicate(@PathVariable long campaignId,
		@AuthenticationPrincipal AuthMember me) {
		return ApiResponse.ok(CampaignResponse.from(campaignService.duplicate(campaignId, me.memberId())));
	}

	@Operation(summary = "종료", description = "ACTIVE·PAUSED → COMPLETED. 진행 중 인스턴스는 CANCELLED. 그 외 상태면 CAMPAIGN_INVALID_STATUS(409).")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
	@PostMapping("/{campaignId}/complete")
	public ApiResponse<CampaignResponse> complete(@PathVariable long campaignId) {
		return ApiResponse.ok(CampaignResponse.from(campaignService.complete(campaignId)));
	}
}
