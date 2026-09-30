package com.withus.common.exception;

/**
 * 업무 규칙 위반. 서비스에서 던지면 GlobalExceptionHandler 가 공통 응답으로 바꾼다
 * 예: throw new BusinessException(SegmentErrorCode.SEGMENT_INVALID_RULE);
 */
public class BusinessException extends RuntimeException {

	private final ErrorCode errorCode;
	private final transient Object details;

	public BusinessException(ErrorCode errorCode) {
		this(errorCode, errorCode.message(), null);
	}

	public BusinessException(ErrorCode errorCode, String message, Object details) {
		super(message);
		this.errorCode = errorCode;
		this.details = details;
	}

	public ErrorCode getErrorCode() {
		return errorCode;
	}

	public Object getDetails() {
		return details;
	}
}
