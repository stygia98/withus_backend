package com.withus.coupon.domain;

import org.springframework.http.HttpStatus;

import com.withus.common.exception.ErrorCode;

/** 쿠폰 오류 코드 (API_SPEC 7·8·12장) */
public enum CouponErrorCode implements ErrorCode {

	COUPON_NOT_FOUND(HttpStatus.NOT_FOUND, "쿠폰을 찾을 수 없습니다."),
	COUPON_INVALID_PERIOD(HttpStatus.BAD_REQUEST, "유효기간 종료일은 시작일보다 빠를 수 없습니다."),
	COUPON_RATE_CAP_REQUIRED(HttpStatus.BAD_REQUEST, "정률 쿠폰은 최대 할인액을 입력해야 합니다."),
	COUPON_ALREADY_ISSUED(HttpStatus.CONFLICT, "발급 이력이 있는 쿠폰은 유효기간 연장만 할 수 있습니다."),
	/** 캠페인·SEND 노드 저장 시 연결 쿠폰의 기간 검사 (팀원2 사용, API_SPEC 6장) */
	COUPON_OUT_OF_PERIOD(HttpStatus.UNPROCESSABLE_CONTENT, "쿠폰 유효기간이 지났거나 아직 시작되지 않았습니다."),
	COUPON_NOT_USABLE(HttpStatus.UNPROCESSABLE_CONTENT, "사용할 수 없는 쿠폰입니다."),
	COUPON_ALREADY_USED(HttpStatus.CONFLICT, "이미 사용한 쿠폰입니다.");

	private final HttpStatus status;
	private final String message;

	CouponErrorCode(HttpStatus status, String message) {
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
