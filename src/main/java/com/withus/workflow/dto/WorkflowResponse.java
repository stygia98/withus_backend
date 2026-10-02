package com.withus.workflow.dto;

import java.util.List;

public record WorkflowResponse(List<WorkflowStepResponse> steps) {
}
