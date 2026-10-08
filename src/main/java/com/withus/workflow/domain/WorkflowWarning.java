package com.withus.workflow.domain;

/** API_SPEC 6장 POST /workflow/validate 응답의 warnings 배열 한 항목 (예: EMAIL_OPENED_UNRELIABLE) */
public record WorkflowWarning(String code, String message) {
}
