package com.withus.workflow.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

public record WorkflowSaveRequest(@NotEmpty(message = "노드를 1개 이상 구성하세요.") @Valid List<WorkflowStepRequest> steps) {
}
