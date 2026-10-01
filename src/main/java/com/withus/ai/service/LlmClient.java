package com.withus.ai.service;

/**
 * LLM 호출 인터페이스 (PRD 2.3: 외부 연동은 인터페이스 뒤에 두고 local 구현을 함께 제공).
 * AI-01·02·03 은 모두 이 인터페이스로만 호출한다.
 *
 * <p>외부 API 호출이므로 DB 트랜잭션 안에서 부르지 않는다 (CLAUDE.md 4장). 호출 전에 데이터를 읽어 두고,
 * 응답을 받은 뒤 저장한다.
 */
public interface LlmClient {

	/**
	 * @throws com.withus.common.exception.BusinessException AI_RATE_LIMITED(429) 한도 초과,
	 *                                                       AI_UNAVAILABLE(503) 그 외 호출 실패
	 */
	LlmResponse generate(LlmRequest request);
}
