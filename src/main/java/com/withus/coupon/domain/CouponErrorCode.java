package com.withus.coupon.domain;

import org.springframework.http.HttpStatus;

import com.withus.common.exception.ErrorCode;

/** 쿠폰 오류 코드 (API_SPEC 7·8·12장) */
public enum CouponErrorCode implements ErrorCode {

	COUPON_NOT_FOUND(HttpStatus.NOT_FOUND, "쿠폰을 찾을 수 없어요."),
	COUPON_INVALID_PERIOD(HttpStatus.BAD_REQUEST, "유효기간 종료일은 시작일보다 빠를 수 없어요."),
	COUPON_RATE_CAP_REQUIRED(HttpStatus.BAD_REQUEST, "정률 쿠폰은 최대 할인액을 입력해야 해요."),
	COUPON_ALREADY_ISSUED(HttpStatus.CONFLICT, "발급 이력이 있는 쿠폰은 유효기간 연장만 할 수 있어요."),
	/** 캠페인·SEND 노드 저장 시 연결 쿠폰의 기간 검사 (팀원2 사용, API_SPEC 6장) */
	COUPON_OUT_OF_PERIOD(HttpStatus.UNPROCESSABLE_CONTENT, "쿠폰 유효기간이 지났거나 아직 시작되지 않았어요."),
	COUPON_NOT_USABLE(HttpStatus.UNPROCESSABLE_CONTENT, "사용할 수 없는 쿠폰이에요."),
	COUPON_ALREADY_USED(HttpStatus.CONFLICT, "이미 사용한 쿠폰이에요."),
	/** 매장 바코드 사용 취소 (API_SPEC 7장) */
	COUPON_NOT_USED(HttpStatus.CONFLICT, "사용하지 않은 쿠폰이라 취소할 수 없어요."),
	COUPON_USED_BY_PURCHASE(HttpStatus.CONFLICT, "구매 등록으로 사용한 쿠폰은 여기서 취소할 수 없어요.");

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
