package com.withus.customer.domain;

import org.springframework.http.HttpStatus;

import com.withus.common.exception.ErrorCode;

/** 고객 오류 코드 (API_SPEC 12장) */
public enum CustomerErrorCode implements ErrorCode {

	CUSTOMER_INVALID_REGION(HttpStatus.BAD_REQUEST, "지역 값이 올바르지 않습니다."),
	CUSTOMER_INVALID_PHONE(HttpStatus.BAD_REQUEST, "휴대폰 번호 형식이 올바르지 않습니다."),
	CUSTOMER_INVALID_DATE(HttpStatus.BAD_REQUEST, "날짜는 YYYY-MM-DD 형식이어야 합니다."),
	CUSTOMER_DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 등록된 이메일입니다.");

	private final HttpStatus status;
	private final String message;

	CustomerErrorCode(HttpStatus status, String message) {
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
