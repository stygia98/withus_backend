package com.withus.ai.domain;

import org.springframework.http.HttpStatus;

import com.withus.common.exception.ErrorCode;

/** AI 오류 코드 (API_SPEC 12장) */
public enum AiErrorCode implements ErrorCode {

	AI_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "AI 호출 한도를 초과했어요. 잠시 후 다시 시도하세요."),
	AI_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI 응답을 받지 못했어요. 잠시 후 다시 시도하세요."),
	/** AI-03: 성공 발송이 없어 요약할 성과가 없다. LLM 을 부르지 않고 저장하지도 않는다 */
	AI_NO_SENT_DATA(HttpStatus.UNPROCESSABLE_ENTITY, "아직 성공한 발송이 없어 요약할 성과가 없어요."),
	/** 프롬프트에 이메일·휴대폰 등 개인정보가 들어 있어 전송을 막았다 (CLAUDE.md 6장 12번) */
	AI_PII_DETECTED(HttpStatus.BAD_REQUEST, "개인정보가 포함된 내용은 AI에 보낼 수 없어요.");

	private final HttpStatus status;
	private final String message;

	AiErrorCode(HttpStatus status, String message) {
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
