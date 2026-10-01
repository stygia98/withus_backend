package com.withus.segment.domain;

import org.springframework.http.HttpStatus;

import com.withus.common.exception.ErrorCode;

/** 세그먼트 오류 코드 (API_SPEC 4장·12장) */
public enum SegmentErrorCode implements ErrorCode {

	SEGMENT_INVALID_RULE(HttpStatus.BAD_REQUEST, "세그먼트 조건이 올바르지 않습니다."),
	SEGMENT_TOO_MANY_CONDITIONS(HttpStatus.BAD_REQUEST, "조건은 최대 10개까지 추가할 수 있습니다.");

	private final HttpStatus status;
	private final String message;

	SegmentErrorCode(HttpStatus status, String message) {
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
