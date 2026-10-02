package com.withus.workflow.domain;

/** API_SPEC 6장 POST /workflow/validate 응답의 checks 배열 한 항목 */
public record WorkflowCheck(String code, boolean passed, String message) {
}
