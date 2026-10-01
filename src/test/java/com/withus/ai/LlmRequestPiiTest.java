package com.withus.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.junit.jupiter.api.Test;

import com.withus.ai.domain.AiErrorCode;
import com.withus.ai.service.LlmRequest;
import com.withus.ai.service.MockLlmClient;
import com.withus.common.exception.BusinessException;

/** LLM 에 고객 개인정보를 보내지 않는다 (CLAUDE.md 6장 12번, PRD 5.3) */
class LlmRequestPiiTest {

	@ParameterizedTest
	@ValueSource(strings = {
		"고객 hong.gildong@example.com 에게 보낼 문구",
		"HONG+tag@Sub.Example.CO.KR 확인",
		"연락처 010-1234-5678 로 안내",
		"연락처 01012345678 로 안내",
		"전화 010 1234 5678",
		"사무실 02-123-4567",
		"대표번호 080-123-4567",
		"지역번호 031-1234-5678"
	})
	void 이메일이나_전화번호가_있으면_요청_생성이_막힌다(String prompt) {
		assertThatThrownBy(() -> LlmRequest.text(null, prompt))
			.isInstanceOfSatisfying(BusinessException.class,
				e -> {
					assertThat(e.getErrorCode()).isEqualTo(AiErrorCode.AI_PII_DETECTED);
					assertThat(e.getMessage()).doesNotContain("010").doesNotContain("@");
				});
	}

	@Test
	void 시스템_지시문에_섞여도_막는다() {
		assertThatThrownBy(() -> LlmRequest.text("문의는 help@withus.com 으로", "문구를 써줘"))
			.isInstanceOfSatisfying(BusinessException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(AiErrorCode.AI_PII_DETECTED));
	}

	@Test
	void 집계_수치와_일반_문장은_통과한다() {
		assertThatCode(() -> LlmRequest.json("성과를 5문장 이내로 요약", """
			{"attempted":10000,"sent":9812,"openRate":0.312,"clickRate":0.068,"conversionRate":0.021,
			 "sentAt":"2026-10-05T09:00:00+09:00","topWeekday":"TUE","hour":10}
			""", null)).doesNotThrowAnyException();
		assertThatCode(() -> LlmRequest.text(null, "20대 여성 타깃, 가을 신상 10% 할인 안내, 친근한 톤"))
			.doesNotThrowAnyException();
	}

	@Test
	void 비어_있는_프롬프트는_거부한다() {
		assertThatThrownBy(() -> LlmRequest.text(null, " ")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void Mock_구현은_지정한_응답을_그대로_돌려주고_없으면_기본값을_쓴다() {
		MockLlmClient mock = new MockLlmClient();

		assertThat(mock.generate(LlmRequest.json(null, "3안", "{\"drafts\":[]}")).text()).isEqualTo("{\"drafts\":[]}");
		assertThat(mock.generate(LlmRequest.json(null, "3안", null)).text()).isEqualTo("{}");
		assertThat(mock.generate(LlmRequest.text(null, "요약")).text()).startsWith("[MOCK]");
		assertThat(mock.generate(LlmRequest.text(null, "요약")).model()).isEqualTo("mock");
	}
}
