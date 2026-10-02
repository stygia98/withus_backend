package com.withus.workflow.dto;

import java.util.Map;

import com.withus.workflow.domain.NodeType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * PUT /campaigns/{id}/workflow, POST /workflow/validate 공용 요청 노드 (API_SPEC 6장).
 * key 는 요청 안에서만 쓰는 임시 식별자 — 서버가 저장 시 step_id 로 바꾼다.
 */
public record WorkflowStepRequest(
	@NotBlank(message = "key 를 입력하세요.") String key,
	@NotNull(message = "nodeType 을 선택하세요.") NodeType nodeType,
	Map<String, Object> config,
	String next,
	String yes,
	String no) {

	public WorkflowStepRequest {
		config = config == null ? Map.of() : config;
	}
}
