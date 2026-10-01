package com.withus.campaign.domain;

import org.springframework.http.HttpStatus;

import com.withus.common.exception.ErrorCode;

/** 캠페인 오류 코드 (API_SPEC 12장) */
public enum CampaignErrorCode implements ErrorCode {

	CAMPAIGN_NOT_FOUND(HttpStatus.NOT_FOUND, "캠페인을 찾을 수 없습니다."),
	CAMPAIGN_INVALID_STATUS(HttpStatus.CONFLICT, "현재 상태에서는 할 수 없는 작업입니다.");

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
