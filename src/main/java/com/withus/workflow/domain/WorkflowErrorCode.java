package com.withus.workflow.domain;

import org.springframework.http.HttpStatus;

import com.withus.common.exception.ErrorCode;

/** 워크플로우 오류 코드 (API_SPEC 12장) */
public enum WorkflowErrorCode implements ErrorCode {

	/** 저장 시 구조 검증 실패 (PRD 6.4). details 에 실패한 검사 메시지 목록 */
	WORKFLOW_INVALID_STRUCTURE(HttpStatus.BAD_REQUEST, "워크플로우 구조가 올바르지 않습니다.");

	private final HttpStatus status;
	private final String message;

	WorkflowErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}

	@Override
	public HttpStatus status() {
		return status;
	}

	@Override
	public String code() {
		return name();
	}

	@Override
	public String message() {
		return message;
	}
}
