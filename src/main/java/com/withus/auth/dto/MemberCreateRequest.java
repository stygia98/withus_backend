package com.withus.auth.dto;

import com.withus.auth.domain.Role;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 사용자 생성 (API_SPEC 2장 POST /members). 초기 비밀번호는 OWNER 가 정해 전달한다 */
public record MemberCreateRequest(
	@NotBlank(message = "이메일을 입력하세요.") @Email(message = "이메일 형식이 아닙니다.") String email,
	@NotBlank(message = "이름을 입력하세요.") @Size(max = 50, message = "이름은 50자까지입니다.") String name,
	@NotNull(message = "역할을 고르세요.") Role role,
	@NotBlank @Size(min = 8, max = 72, message = "비밀번호는 8~72자입니다.") String password) {
}
