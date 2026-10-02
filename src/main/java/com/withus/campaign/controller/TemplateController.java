package com.withus.campaign.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.withus.auth.security.AuthMember;
import com.withus.campaign.domain.Template;
import com.withus.campaign.dto.TemplateCreateRequest;
import com.withus.campaign.dto.TemplateResponse;
import com.withus.campaign.dto.TemplateUpdateRequest;
import com.withus.campaign.service.TemplateService;
import com.withus.campaign.service.TemplatePreviewService;
import com.withus.campaign.dto.TemplatePreviewRequest;
import com.withus.campaign.dto.TemplatePreviewResponse;
import com.withus.common.domain.Channel;
import com.withus.common.response.ApiResponse;
import com.withus.common.response.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@Tag(name = "템플릿", description = "메일·SMS 템플릿 CRUD·복제 (API_SPEC 5장)")
@RestController
@RequestMapping("/api/v1/templates")
public class TemplateController {

	private final TemplateService templateService;
	private final TemplatePreviewService templatePreviewService;

	public TemplateController(TemplateService templateService, TemplatePreviewService templatePreviewService) {
		this.templateService = templateService;
		this.templatePreviewService = templatePreviewService;
	}

	@Operation(summary = "템플릿 목록", description = "channel 필터(EMAIL/SMS, 생략 시 전체). page 는 0부터, size 기본 20.")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER','STAFF')")
	@GetMapping
	public ApiResponse<PageResponse<TemplateResponse>> list(
		@RequestParam(required = false) Channel channel,
		@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "20") int size) {
		PageResponse<Template> result = templateService.list(channel, page, size);
		PageResponse<TemplateResponse> body = PageResponse.of(
			result.content().stream().map(TemplateResponse::from).toList(),
			result.page(), result.size(), result.totalElements());
		return ApiResponse.ok(body);
	}

	@Operation(summary = "템플릿 상세", description = "SCHEDULED·ACTIVE·PAUSED 캠페인이 쓰고 있으면 inUse=true.")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER','STAFF')")
	@GetMapping("/{templateId}")
	public ApiResponse<TemplateResponse> detail(@PathVariable long templateId) {
		Template template = templateService.getOrThrow(templateId);
		return ApiResponse.ok(TemplateResponse.from(template, templateService.isInUse(templateId)));
	}

	@Operation(summary = "템플릿 생성", description = "오류: TEMPLATE_SUBJECT_REQUIRED(400), TEMPLATE_INVALID_PLACEHOLDER(400).")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER','STAFF')")
	@PostMapping
	public ApiResponse<TemplateResponse> create(@Valid @RequestBody TemplateCreateRequest request,
		@AuthenticationPrincipal AuthMember me) {
		Template template = templateService.create(request.toTemplate(), me.memberId());
		return ApiResponse.ok(TemplateResponse.from(template));
	}

	@Operation(summary = "템플릿 수정", description = "사용 중이면 TEMPLATE_IN_USE(409).")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER','STAFF')")
	@PutMapping("/{templateId}")
	public ApiResponse<TemplateResponse> update(@PathVariable long templateId,
		@Valid @RequestBody TemplateUpdateRequest request) {
		Template template = templateService.update(templateId, request.toTemplate());
		return ApiResponse.ok(TemplateResponse.from(template));
	}

	@Operation(summary = "템플릿 삭제", description = "캠페인·워크플로우가 참조 중이면 TEMPLATE_IN_USE(409).")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER','STAFF')")
	@DeleteMapping("/{templateId}")
	public ApiResponse<Void> delete(@PathVariable long templateId) {
		templateService.delete(templateId);
		return ApiResponse.ok(null);
	}

	@Operation(summary = "템플릿 복제")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER','STAFF')")
	@PostMapping("/{templateId}/duplicate")
	public ApiResponse<TemplateResponse> duplicate(@PathVariable long templateId,
		@AuthenticationPrincipal AuthMember me) {
		Template copy = templateService.duplicate(templateId, me.memberId());
		return ApiResponse.ok(TemplateResponse.from(copy));
	}

	@Operation(summary = "렌더링 미리보기",
		description = "sampleCustomerId 고객 값으로 치환한 결과를 돌려준다(STAFF 는 고객 조회 권한이 없어 sampleCustomerId 를 무시하고 고정 샘플 값을 쓴다). "
			+ "(광고) 문구는 포함하고 쿠폰 발급·추적 치환은 하지 않는다. segmentId 가 있으면 기본값으로 나갈 인원도 계산한다. "
			+ "메일 html 은 sandbox iframe 으로만 렌더링하고, SMS 는 평문 text 로 돌려준다.")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER','STAFF')")
	@PostMapping("/{templateId}/preview")
	public ApiResponse<TemplatePreviewResponse> preview(@PathVariable long templateId,
		@Valid @RequestBody TemplatePreviewRequest request, Authentication authentication) {
		boolean canReadCustomer = authentication.getAuthorities().stream()
			.anyMatch(a -> a.getAuthority().equals("ROLE_OWNER") || a.getAuthority().equals("ROLE_MANAGER"));
		return ApiResponse.ok(templatePreviewService.preview(templateId, request.sampleCustomerId(),
			request.segmentId(), canReadCustomer));
	}
}
