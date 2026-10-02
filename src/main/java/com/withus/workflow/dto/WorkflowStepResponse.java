package com.withus.workflow.dto;

import java.util.Map;

import com.withus.workflow.domain.NodeType;

public record WorkflowStepResponse(long stepId, NodeType nodeType, Map<String, Object> config, Long next, Long yes,
		Long no, short depth) {
}
