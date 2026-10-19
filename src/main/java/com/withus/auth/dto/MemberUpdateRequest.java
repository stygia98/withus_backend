package com.withus.auth.dto;

import com.withus.auth.domain.Role;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * 계정 수정 (API_SPEC 2장 PATCH /members/{id}). 보내지 않은 값(null)은 그대로 둔다.
 * 이름·이메일은 자기 계정도 바꿀 수 있고, 역할·활성 여부는 자기 계정이면 MEMBER_SELF_CHANGE
 */
public record MemberUpdateRequest(
	Role role,
	Boolean active,
	@Size(min = 1, max = 50, message = "이름은 1~50자예요.") String name,
	@Email(message = "이메일 형식이 아닙니다.") @Size(max = 255, message = "이메일은 255자까지예요.") String email) {
}
