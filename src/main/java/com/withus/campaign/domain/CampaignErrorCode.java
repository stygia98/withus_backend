package com.withus.campaign.domain;

import org.springframework.http.HttpStatus;

import com.withus.common.exception.ErrorCode;

/** 캠페인 오류 코드 (API_SPEC 12장) */
public enum CampaignErrorCode implements ErrorCode {

	CAMPAIGN_NOT_FOUND(HttpStatus.NOT_FOUND, "캠페인을 찾을 수 없습니다."),
	CAMPAIGN_INVALID_STATUS(HttpStatus.CONFLICT, "현재 상태에서는 할 수 없는 작업입니다."),
	/** 템플릿에 {{couponUrl}} 치환자가 있는데 캠페인에 쿠폰이 연결돼 있지 않음 (API_SPEC 6장) */
	CAMPAIGN_COUPON_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT, "템플릿에 쿠폰 링크가 있어 쿠폰을 연결해야 합니다.");

	private final HttpStatus status;
	private final String message;

	CampaignErrorCode(HttpStatus status, String message) {
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
