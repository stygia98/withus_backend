package com.withus.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.withus.ai.service.GeminiLlmClient;
import com.withus.ai.service.LlmClient;
import com.withus.ai.service.MockLlmClient;

/** withus.ai.type 에 따라 LlmClient 구현이 바뀐다 — 프로필(환경변수) 전환만으로 운영 구성이 된다 (PRD 2.3) */
class AiConfigTest {

	@Nested
	@SpringBootTest
	class LocalDefault {

		@Autowired
		LlmClient llmClient;

		@Test
		void local_프로필_기본값은_Mock이다() {
			assertThat(llmClient).isInstanceOf(MockLlmClient.class);
		}
	}

	@Nested
	@SpringBootTest(properties = "withus.ai.type=gemini")
	class GeminiType {

		@Autowired
		LlmClient llmClient;

		@Test
		void type이_gemini면_Gemini_구현이_선택된다() {
			assertThat(llmClient).isInstanceOf(GeminiLlmClient.class);
		}
	}
}
