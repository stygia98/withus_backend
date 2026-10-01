package com.withus.customer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 고객 수정 (API_SPEC 3장 PATCH /customers/{id}). 수정 화면의 값을 통째로 받는다(빈 값은 지움)
 * 누적구매액은 구매 등록으로만, 수신동의는 PATCH /consent 로만 바뀐다
 */
public record CustomerUpdateRequest(
	@Size(max = 50) String name,
	@NotBlank(message = "이메일을 입력하세요.") @Pattern(regexp = EMAIL, message = "이메일 형식이 아닙니다.") String email,
	@Schema(example = "010-1234-5678") String phone,
	@Schema(description = "시·도명 또는 코드", example = "서울") String region,
	@Schema(example = "1998-04-12") String birthDate,
	@NotBlank(message = "가입일을 입력하세요.") @Schema(example = "2026-09-30") String joinedAt) {

	/** 앞뒤 공백은 정규화에서 지우므로 허용한다 (@Email 은 공백이 있으면 거부) */
	static final String EMAIL = "\\s*[^\\s@]+@[^\\s@]+\\.[^\\s@]+\\s*";
}
