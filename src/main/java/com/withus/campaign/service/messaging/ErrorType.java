package com.withus.campaign.service.messaging;

/** PRD 8.2: TRANSIENT 는 1·5·15분 재시도, PERMANENT 는 즉시 FAILED */
public enum ErrorType {
	TRANSIENT, PERMANENT
}
