package com.withus.customer.dto;

import com.withus.common.domain.Channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 수신동의 변경 (API_SPEC 3장 PATCH /customers/{id}/consent) */
public record ConsentUpdateRequest(
	@NotNull(message = "채널을 선택하세요.") Channel channel,
	@NotBlank @Pattern(regexp = "[YN]", message = "Y 또는 N") String consent,
	@Size(max = 500)
	@Schema(description = "suppression 에 있는 채널을 Y로 바꿀 때 필수", example = "2026-09-30 매장 방문 시 서면 재동의")
	String evidenceNote) {
}
