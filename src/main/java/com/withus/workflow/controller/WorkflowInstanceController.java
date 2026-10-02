package com.withus.workflow.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.withus.common.response.ApiResponse;
import com.withus.common.response.PageResponse;
import com.withus.workflow.domain.InstanceStatus;
import com.withus.workflow.dto.WorkflowInstanceResponse;
import com.withus.workflow.service.WorkflowService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "워크플로우", description = "워크플로우 구조 저장·조회·검증 (API_SPEC 6장, PRD 6.4)")
@RestController
@RequestMapping("/api/v1/campaigns/{campaignId}/instances")
public class WorkflowInstanceController {

	private final WorkflowService workflowService;

	public WorkflowInstanceController(WorkflowService workflowService) {
		this.workflowService = workflowService;
	}

	@Operation(summary = "인스턴스 목록", description = "status 필터(생략 시 전체). page 는 0부터, size 기본 20.")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
	@GetMapping
	public ApiResponse<PageResponse<WorkflowInstanceResponse>> list(@PathVariable long campaignId,
		@RequestParam(required = false) InstanceStatus status, @RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "20") int size) {
		return ApiResponse.ok(workflowService.listInstances(campaignId, status, page, size));
	}
}
