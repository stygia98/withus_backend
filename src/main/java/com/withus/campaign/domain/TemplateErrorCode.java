package com.withus.campaign.domain;

import org.springframework.http.HttpStatus;

import com.withus.common.exception.ErrorCode;

/** 템플릿 오류 코드 (API_SPEC 12장) */
public enum TemplateErrorCode implements ErrorCode {

	TEMPLATE_NOT_FOUND(HttpStatus.NOT_FOUND, "템플릿을 찾을 수 없습니다."),
	TEMPLATE_IN_USE(HttpStatus.CONFLICT, "사용 중인 템플릿은 수정·삭제할 수 없습니다."),
	TEMPLATE_SUBJECT_REQUIRED(HttpStatus.BAD_REQUEST, "메일 템플릿은 제목이 필요합니다."),
	TEMPLATE_INVALID_PLACEHOLDER(HttpStatus.BAD_REQUEST, "허용되지 않은 치환자가 있습니다."),
	TEMPLATE_AD_COPY_NOT_ALLOWED(HttpStatus.BAD_REQUEST,
		"(광고) 표기·수신거부 문구·수신거부 번호는 발송할 때 자동으로 들어가므로 직접 쓸 수 없습니다.");

	private final HttpStatus status;
	private final String message;

	TemplateErrorCode(HttpStatus status, String message) {
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
