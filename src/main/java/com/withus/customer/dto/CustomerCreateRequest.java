package com.withus.customer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 개별 등록 (API_SPEC 3장 POST /customers). 정규화 전 원본 값을 받는다 */
public record CustomerCreateRequest(
	@Size(max = 50) String name,
	@NotBlank(message = "이메일을 입력하세요.")
	@Pattern(regexp = CustomerUpdateRequest.EMAIL, message = "이메일 형식이 아닙니다.") String email,
	@Schema(example = "010-1234-5678") String phone,
	@Schema(description = "시·도명 또는 코드", example = "서울") String region,
	@Schema(example = "1998-04-12") String birthDate,
	@NotBlank(message = "가입일을 입력하세요.") @Schema(example = "2026-09-30") String joinedAt,
	@Pattern(regexp = "[YN]", message = "Y 또는 N") @Schema(description = "생략 시 N") String emailConsent,
	@Pattern(regexp = "[YN]", message = "Y 또는 N") @Schema(description = "생략 시 N") String smsConsent) {
}
