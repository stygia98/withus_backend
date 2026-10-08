package com.withus.auth.domain;

import org.springframework.http.HttpStatus;

import com.withus.common.exception.ErrorCode;

/** 인증·권한 오류 코드 (API_SPEC 12장) */
public enum AuthErrorCode implements ErrorCode {

	AUTH_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
	AUTH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "로그인이 만료되었습니다."),
	AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
	AUTH_ACCOUNT_LOCKED(HttpStatus.UNAUTHORIZED, "로그인 5회 실패로 잠시 잠겼습니다. 잠시 후 다시 시도하세요."),
	AUTH_ACCOUNT_INACTIVE(HttpStatus.UNAUTHORIZED, "비활성화된 계정입니다."),
	AUTH_FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다."),
	AUTH_CSRF_INVALID(HttpStatus.FORBIDDEN, "보안 토큰이 없거나 올바르지 않습니다. 새로고침 후 다시 시도하세요."),
	// 사용자 관리 (API_SPEC 2장)
	MEMBER_DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 등록된 이메일입니다."),
	MEMBER_SELF_CHANGE(HttpStatus.BAD_REQUEST, "자기 계정의 역할·활성 여부는 바꿀 수 없습니다.");

	private final HttpStatus status;
	private final String message;

	AuthErrorCode(HttpStatus status, String message) {
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
