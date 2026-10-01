package com.withus.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * AI-01 입력 (PRD 5.3, API_SPEC 11장). 고객 이름·이메일·휴대폰을 넣지 않는다 —
 * 이메일·전화번호 패턴이 있으면 AI 로 보내지 않고 AI_PII_DETECTED(400)로 거절한다.
 */
public record CopyDraftRequest(
	@NotBlank(message = "목적을 입력하세요.") @Size(max = 200, message = "목적은 200자 이하입니다.") String purpose,
	@NotBlank(message = "타깃을 입력하세요.") @Size(max = 200, message = "타깃은 200자 이하입니다.") String target,
	@NotBlank(message = "톤을 입력하세요.") @Size(max = 50, message = "톤은 50자 이하입니다.") String tone,
	@NotBlank(message = "핵심 메시지를 입력하세요.") @Size(max = 500, message = "핵심 메시지는 500자 이하입니다.") String keyMessage) {
}
