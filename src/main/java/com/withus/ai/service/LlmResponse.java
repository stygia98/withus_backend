package com.withus.ai.service;

/**
 * @param text      생성된 텍스트 (비어 있지 않음)
 * @param model     실제 사용한 모델 (ai_report.model 에 저장)
 * @param truncated 출력 토큰 한도(MAX_TOKENS)로 잘렸는가 — JSON 응답이면 파싱 실패 가능성이 있다
 */
public record LlmResponse(String text, String model, boolean truncated) {
}
