package com.withus.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.withus.common.exception.ErrorCode;

/**
 * 모든 API의 공통 응답 형식 { success, data, error } (API_SPEC 1.2)
 */
public record ApiResponse<T>(boolean success, T data, ErrorBody error) {

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ErrorBody(String code, String message, Object details) {
	}

	public static <T> ApiResponse<T> ok(T data) {
		return new ApiResponse<>(true, data, null);
	}

	public static ApiResponse<Void> fail(ErrorCode errorCode, String message, Object details) {
		return new ApiResponse<>(false, null, new ErrorBody(errorCode.code(), message, details));
	}

	public static ApiResponse<Void> fail(ErrorCode errorCode) {
		return fail(errorCode, errorCode.message(), null);
	}
}
