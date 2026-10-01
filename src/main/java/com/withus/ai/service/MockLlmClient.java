package com.withus.ai.service;

/**
 * local 프로필용 구현 — 외부 호출 없이 고정 응답을 돌려준다 (무료 등급 한도를 쓰지 않고 화면·흐름을 개발).
 * 호출하는 쪽이 {@link LlmRequest#mockText()} 로 실제 형식에 맞는 응답을 주면 그대로 돌려준다.
 */
public class MockLlmClient implements LlmClient {

	public static final String MODEL = "mock";

	@Override
	public LlmResponse generate(LlmRequest request) {
		String text = request.mockText();
		if (text == null || text.isBlank()) {
			text = request.json() ? "{}" : "[MOCK] AI 응답 예시입니다.";
		}
		return new LlmResponse(text, MODEL, false);
	}
}
