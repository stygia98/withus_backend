package com.withus.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 사용자 비밀번호 재설정 (API_SPEC 2장 POST /members/{memberId}/password). 새 비밀번호는 OWNER 가 정해 전달한다 */
public record PasswordResetRequest(
	@NotBlank @Size(min = 8, max = 72, message = "새 비밀번호는 8~72자예요.") String newPassword) {
}
