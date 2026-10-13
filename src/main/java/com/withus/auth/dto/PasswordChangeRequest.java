package com.withus.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 내 비밀번호 변경 (API_SPEC 2장 POST /auth/password). 현재 비밀번호를 확인한 뒤에만 바꾼다 */
public record PasswordChangeRequest(
	@NotBlank(message = "현재 비밀번호를 입력하세요.") String currentPassword,
	@NotBlank @Size(min = 8, max = 72, message = "새 비밀번호는 8~72자예요.") String newPassword) {
}
