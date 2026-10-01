package com.withus.campaign.service.messaging;

/** success=false 일 때 errorType 으로 재시도 여부를 가른다 (PRD 8.2) */
public record SendResult(boolean success, String providerMessageId, ErrorType errorType, String errorMessage) {

	public static SendResult success(String providerMessageId) {
		return new SendResult(true, providerMessageId, null, null);
	}

	public static SendResult failure(ErrorType errorType, String errorMessage) {
		return new SendResult(false, null, errorType, errorMessage);
	}
}
