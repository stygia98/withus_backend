package com.withus.campaign.controller;

import org.springframework.security.access.prepost.PreAuthorize;
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

	public TemplateController(TemplateService templateService) {
		this.templateService = templateService;
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
}
