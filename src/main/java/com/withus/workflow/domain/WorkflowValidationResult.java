package com.withus.workflow.domain;

import java.util.List;

public record WorkflowValidationResult(boolean valid, List<WorkflowCheck> checks, List<WorkflowWarning> warnings) {
}
