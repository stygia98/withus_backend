package com.withus.ai.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.withus.ai.dto.CampaignPlan;
import com.withus.ai.dto.CampaignPlanApplyResponse;
import com.withus.ai.dto.CampaignPlanExamplesRequest;
import com.withus.ai.dto.CampaignPlanExamplesResponse;
import com.withus.ai.dto.CampaignPlanRequest;
import com.withus.ai.dto.CampaignPlanResponse;
import com.withus.ai.dto.CampaignReportResponse;
import com.withus.ai.dto.CopyDraftRequest;
import com.withus.ai.dto.CopyDraftResponse;
import com.withus.ai.dto.SendTimeRecommendationResponse;
import com.withus.ai.service.CampaignPlanService;
import com.withus.ai.service.CampaignReportService;
import com.withus.ai.service.CopyDraftService;
import com.withus.ai.service.SendTimeRecommendationService;
import com.withus.auth.security.AuthMember;
import com.withus.common.response.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@Tag(name = "AI", description = "AI-01 문구 초안 등 (API_SPEC 11장). Gemini 무료 등급 — 한도 초과 시 잠시 후 재시도")
@RestController
@RequestMapping("/api/v1/ai")
public class AiController {

	private final CopyDraftService copyDraftService;
	private final SendTimeRecommendationService sendTimeRecommendationService;

	private final CampaignReportService campaignReportService;
	private final CampaignPlanService campaignPlanService;

	public AiController(CopyDraftService copyDraftService,
		SendTimeRecommendationService sendTimeRecommendationService, CampaignReportService campaignReportService,
		CampaignPlanService campaignPlanService) {
		this.copyDraftService = copyDraftService;
		this.sendTimeRecommendationService = sendTimeRecommendationService;
		this.campaignReportService = campaignReportService;
		this.campaignPlanService = campaignPlanService;
	}

	@Operation(summary = "AI-01 메일 문구 초안 3안",
		description = "본문은 빈 줄로 문단을 나눈 평문, 치환자는 {{name|고객}} 만. "
			+ "오류: AI_PII_DETECTED(400, 입력에 이메일·전화번호), AI_RATE_LIMITED(429), AI_UNAVAILABLE(503).")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER','STAFF')")
	@PostMapping("/copy-drafts")
	public ApiResponse<CopyDraftResponse> copyDrafts(@Valid @RequestBody CopyDraftRequest request) {
		return ApiResponse.ok(copyDraftService.generate(request));
	}

	/**
	 * adYn 은 API 계약상 받지만 판정에는 쓰지 않는다 — 20:50 종료 가드레일을 광고 여부와 관계없이 항상 적용하기로 했다
	 * (2026-10-01 결정, PRD 5.3 "항상 적용").
	 */
	@Operation(summary = "AI-02 최적 발송 시간 추천",
		description = "최근 90일 사람 오픈·클릭(클릭 2 : 오픈 1)으로 요일·시간대 상위 3개. 시작 08:00~20:00, "
			+ "시작 + (PENDING + targetCount) ÷ 초당 한도 ≤ 20:50 인 후보만. 이벤트 100건 미만이면 dataSufficient=false, "
			+ "WEEKDAY 10:00 1건. 근거 문장은 Gemini 가 쓰고, 실패하면 서버 문장으로 대신한다.")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
	@GetMapping("/send-time-recommendations")
	public ApiResponse<SendTimeRecommendationResponse> sendTimeRecommendations(@RequestParam long targetCount,
		@RequestParam(required = false) String adYn) {
		return ApiResponse.ok(sendTimeRecommendationService.recommend(targetCount));
	}

	@Operation(summary = "AI-03 캠페인 성과 요약 생성·재생성",
		description = "집계 지표만 Gemini 에 보내 5문장 이내 요약을 ai_report 에 새로 저장한다(캠페인별 최근 10건만 남김). "
			+ "성공 발송이 없으면 LLM 을 부르지 않고 저장하지도 않는다 — AI_NO_SENT_DATA(422).")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
	@PostMapping("/reports/campaigns/{campaignId}")
	public ApiResponse<CampaignReportResponse> generateReport(@PathVariable long campaignId) {
		return ApiResponse.ok(campaignReportService.generate(campaignId));
	}

	@Operation(summary = "AI-03 최근 성과 요약 조회", description = "아직 요약이 없으면 data 는 null.")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER','STAFF')")
	@GetMapping("/reports/campaigns/{campaignId}")
	public ApiResponse<CampaignReportResponse> latestReport(@PathVariable long campaignId) {
		return ApiResponse.ok(campaignReportService.latest(campaignId));
	}

	@Operation(summary = "AI-04 캠페인 기획 초안",
		description = "목표 한 줄로 타겟팅·쿠폰·메시지·일회성 캠페인 초안을 만든다. 저장하지 않는다. "
			+ "오류: AI_PII_DETECTED(400), AI_RATE_LIMITED(429), AI_UNAVAILABLE(503, 형식·규칙이 맞지 않으면 다시 만들기).")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
	@PostMapping("/campaign-plans")
	public ApiResponse<CampaignPlanResponse> plan(@Valid @RequestBody CampaignPlanRequest request) {
		return ApiResponse.ok(campaignPlanService.plan(request.goal()));
	}

	@Operation(summary = "AI-04 목표 예시 새로 만들기",
		description = "목표 입력칸 아래 예시 4개를 새로 만든다. 저장하지 않으며 고객 데이터는 보내지 않는다. "
			+ "current(지금 보이는 예시)와 겹치지 않게 요청한다. 오류: AI_RATE_LIMITED(429), AI_UNAVAILABLE(503).")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
	@PostMapping("/campaign-plans/examples")
	public ApiResponse<CampaignPlanExamplesResponse> planExamples(
		@Valid @RequestBody CampaignPlanExamplesRequest request) {
		return ApiResponse.ok(new CampaignPlanExamplesResponse(campaignPlanService.examples(request.current())));
	}

	@Operation(summary = "AI-04 캠페인 기획 초안 저장",
		description = "고친 초안으로 타겟팅·쿠폰·템플릿·캠페인(DRAFT)을 한 트랜잭션에서 만든다. LLM 을 부르지 않는다. "
			+ "각 도메인 검증 오류(SEGMENT_INVALID_RULE, COUPON_INVALID_PERIOD, TEMPLATE_AD_COPY_NOT_ALLOWED 등)가 나면 아무것도 저장하지 않는다.")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
	@PostMapping("/campaign-plans/apply")
	public ApiResponse<CampaignPlanApplyResponse> applyPlan(@Valid @RequestBody CampaignPlan plan,
		@AuthenticationPrincipal AuthMember me) {
		return ApiResponse.ok(campaignPlanService.apply(plan, me.memberId()));
	}
}
