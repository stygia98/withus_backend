package com.withus.workflow.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.withus.common.response.ApiResponse;
import com.withus.workflow.domain.WorkflowValidationResult;
import com.withus.workflow.dto.WorkflowResponse;
import com.withus.workflow.dto.WorkflowSaveRequest;
import com.withus.workflow.service.WorkflowService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@Tag(name = "워크플로우", description = "워크플로우 구조 저장·조회·검증 (API_SPEC 6장, PRD 6.4)")
@RestController
@RequestMapping("/api/v1/campaigns/{campaignId}/workflow")
public class WorkflowController {

	private final WorkflowService workflowService;

	public WorkflowController(WorkflowService workflowService) {
		this.workflowService = workflowService;
	}

	@Operation(summary = "워크플로우 노드 조회")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER','STAFF')")
	@GetMapping
	public ApiResponse<WorkflowResponse> get(@PathVariable long campaignId) {
		return ApiResponse.ok(workflowService.get(campaignId));
	}

	@Operation(summary = "워크플로우 저장", description = "DRAFT 상태만 저장할 수 있다. 저장 시 구조를 검증한다. "
		+ "오류: CAMPAIGN_INVALID_STATUS(409), WORKFLOW_INVALID_STRUCTURE(400, details에 위반 목록).")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
	@PutMapping
	public ApiResponse<WorkflowResponse> save(@PathVariable long campaignId,
		@Valid @RequestBody WorkflowSaveRequest request) {
		return ApiResponse.ok(workflowService.save(campaignId, request));
	}

	@Operation(summary = "구조 검사만 수행", description = "저장하지 않고 checks·warnings만 돌려준다.")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
	@PostMapping("/validate")
	public ApiResponse<WorkflowValidationResult> validate(@PathVariable long campaignId,
		@Valid @RequestBody WorkflowSaveRequest request) {
		return ApiResponse.ok(workflowService.validateOnly(campaignId, request));
	}
}
