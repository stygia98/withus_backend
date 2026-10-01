package com.withus.campaign.domain;

import org.springframework.http.HttpStatus;

import com.withus.common.exception.ErrorCode;

/** 파일 업로드 오류 코드 (API_SPEC 12장) */
public enum FileErrorCode implements ErrorCode {

	UPLOAD_FILE_TOO_LARGE(HttpStatus.BAD_REQUEST, "파일 크기가 5MB를 초과했습니다."),
	FILE_INVALID_TYPE(HttpStatus.BAD_REQUEST, "jpg·png·gif 파일만 업로드할 수 있습니다.");

	private final HttpStatus status;
	private final String message;

	FileErrorCode(HttpStatus status, String message) {
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
