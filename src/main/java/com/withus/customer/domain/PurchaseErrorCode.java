package com.withus.customer.domain;

import org.springframework.http.HttpStatus;

import com.withus.common.exception.ErrorCode;

/**
 * 구매 등록의 쿠폰 오류 (API_SPEC 3장 POST /customers/{id}/purchases, 12장).
 * 코드 문자열은 coupon 도메인 것이지만 이 경로에서만 쓰므로 고객 구간에 둔다. 팀원3 쪽에 같은 코드가 생겨도 문자열이 같아 응답은 동일하다
 */
public enum PurchaseErrorCode implements ErrorCode {

	COUPON_NOT_USABLE(HttpStatus.UNPROCESSABLE_CONTENT, "이 고객이 사용할 수 없는 쿠폰입니다."),
	COUPON_ALREADY_USED(HttpStatus.CONFLICT, "이미 사용된 쿠폰입니다.");

	private final HttpStatus status;
	private final String message;

	PurchaseErrorCode(HttpStatus status, String message) {
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
