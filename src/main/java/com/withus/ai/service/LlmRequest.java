package com.withus.ai.service;

/**
 * LLM 요청. 생성 시점에 개인정보 패턴을 검사하므로 어떤 구현체로 가도 이 관문을 지난다.
 *
 * @param systemInstruction 역할·형식 지시 (선택)
 * @param prompt            사용자 입력. 고객 이름·이메일·휴대폰을 넣지 않는다
 * @param json              true 면 JSON 응답을 요청한다 (responseMimeType = application/json)
 * @param temperature       null 이면 모델 기본값
 * @param maxOutputTokens   null 이면 모델 기본값
 * @param mockText          Mock 구현이 돌려줄 응답. 없으면 기본 문구 (local 화면 개발용)
 */
public record LlmRequest(String systemInstruction, String prompt, boolean json, Double temperature,
	Integer maxOutputTokens, String mockText) {

	public LlmRequest {
		if (prompt == null || prompt.isBlank()) {
			throw new IllegalArgumentException("prompt 는 비어 있을 수 없습니다");
		}
		PiiGuard.assertNoPii(systemInstruction, prompt);
	}

	public static LlmRequest text(String systemInstruction, String prompt) {
		return new LlmRequest(systemInstruction, prompt, false, null, null, null);
	}

	public static LlmRequest json(String systemInstruction, String prompt, String mockJson) {
		return new LlmRequest(systemInstruction, prompt, true, null, null, mockJson);
	}
}
