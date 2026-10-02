package com.withus.workflow.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record WorkflowSaveRequest(@NotEmpty(message = "노드를 1개 이상 구성하세요.") @Size(max = 50, message = "노드가 너무 많습니다.") @Valid List<WorkflowStepRequest> steps) {
}
