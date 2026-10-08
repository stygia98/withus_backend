package com.withus.customer.domain;

import org.springframework.http.HttpStatus;

import com.withus.common.exception.ErrorCode;

/** 고객 오류 코드 (API_SPEC 12장) */
public enum CustomerErrorCode implements ErrorCode {

	CUSTOMER_INVALID_REGION(HttpStatus.BAD_REQUEST, "지역 값이 올바르지 않습니다."),
	CUSTOMER_INVALID_PHONE(HttpStatus.BAD_REQUEST, "휴대폰 번호 형식이 올바르지 않습니다."),
	CUSTOMER_INVALID_DATE(HttpStatus.BAD_REQUEST, "날짜는 YYYY-MM-DD 형식이어야 합니다."),
	CUSTOMER_DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 등록된 이메일입니다."),
	CUSTOMER_CONSENT_EVIDENCE_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT, "수신거부 이력이 있어 재동의 증빙 메모가 필요합니다."),
	// 업로드 행별 실패 사유 (응답 failures[].reason)
	CUSTOMER_INVALID_EMAIL(HttpStatus.BAD_REQUEST, "이메일이 없거나 형식이 올바르지 않습니다."),
	CUSTOMER_INVALID_NAME(HttpStatus.BAD_REQUEST, "이름은 50자까지 입력할 수 있습니다."),
	CUSTOMER_INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "누적구매액은 0 이상의 정수여야 합니다."),
	CUSTOMER_INVALID_CONSENT(HttpStatus.BAD_REQUEST, "수신동의는 Y 또는 N 이어야 합니다."),
	// 업로드 파일 전체 오류 (API_SPEC 3장)
	UPLOAD_FILE_TOO_LARGE(HttpStatus.BAD_REQUEST, "파일은 10MB까지 올릴 수 있습니다."),
	UPLOAD_TOO_MANY_ROWS(HttpStatus.BAD_REQUEST, "한 번에 10,000행까지 올릴 수 있습니다."),
	UPLOAD_INVALID_HEADER(HttpStatus.BAD_REQUEST, "첫 행이 업로드 양식의 헤더와 다릅니다. 양식 파일을 내려받아 쓰세요."),
	UPLOAD_INVALID_FILE(HttpStatus.BAD_REQUEST, "xlsx 또는 csv 파일만 올릴 수 있습니다."),
	// 수신거부 (API_SPEC 8장). 실패 사유는 구분하지 않는다 (PRD 8.3)
	UNSUBSCRIBE_INVALID_TOKEN(HttpStatus.BAD_REQUEST, "유효하지 않은 링크입니다.");

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
