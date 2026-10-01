package com.withus.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.withus.ai.config.AiProperties;
import com.withus.ai.domain.AiErrorCode;
import com.withus.ai.service.GeminiLlmClient;
import com.withus.ai.service.LlmRequest;
import com.withus.ai.service.LlmResponse;
import com.withus.common.exception.BusinessException;

/** Gemini 호출 구현 (PRD 5.3: 한도 초과 오류 안내, 개인정보 미전송). 외부 호출 없이 MockRestServiceServer 로 검증한다 */
class GeminiLlmClientTest {

	private static final String BASE = "https://gemini.test/v1beta";
	private static final String URL = BASE + "/models/gemini-test:generateContent";

	private MockRestServiceServer server;
	private GeminiLlmClient client;

	@BeforeEach
	void setUp() {
		client = clientWithKey("secret-key");
	}

	private GeminiLlmClient clientWithKey(String apiKey) {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
		server = MockRestServiceServer.bindTo(builder).build();
		AiProperties.Gemini props = new AiProperties.Gemini(apiKey, "gemini-test", BASE, Duration.ofSeconds(3),
			Duration.ofSeconds(30));
		return new GeminiLlmClient(builder.build(), props);
	}

	@Test
	void 정상_응답의_텍스트를_돌려주고_키는_헤더로만_보낸다() {
		server.expect(requestTo(URL))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("x-goog-api-key", "secret-key"))
			.andExpect(jsonPath("$.contents[0].role").value("user"))
			.andExpect(jsonPath("$.contents[0].parts[0].text").value("신제품 안내 문구를 써줘"))
			.andExpect(jsonPath("$.systemInstruction.parts[0].text").value("너는 마케터야"))
			.andRespond(withSuccess("""
				{"candidates":[{"content":{"role":"model","parts":[{"text":"안녕하세요"}]},"finishReason":"STOP"}]}
				""", MediaType.APPLICATION_JSON));

		LlmResponse response = client.generate(LlmRequest.text("너는 마케터야", "신제품 안내 문구를 써줘"));

		assertThat(response.text()).isEqualTo("안녕하세요");
		assertThat(response.model()).isEqualTo("gemini-test");
		assertThat(response.truncated()).isFalse();
		server.verify();
	}

	@Test
	void JSON_모드면_responseMimeType을_요청한다() {
		server.expect(requestTo(URL))
			.andExpect(jsonPath("$.generationConfig.responseMimeType").value("application/json"))
			.andRespond(withSuccess("""
				{"candidates":[{"content":{"parts":[{"text":"{\\"ok\\":true}"}]},"finishReason":"STOP"}]}
				""", MediaType.APPLICATION_JSON));

		LlmResponse response = client.generate(LlmRequest.json(null, "3안을 JSON 으로", null));

		assertThat(response.text()).isEqualTo("{\"ok\":true}");
		server.verify();
	}

	@Test
	void 여러_파트는_이어붙이고_MAX_TOKENS면_잘림_표시를_한다() {
		server.expect(requestTo(URL))
			.andRespond(withSuccess("""
				{"candidates":[{"content":{"parts":[{"text":"앞부분 "},{"text":"뒷부분"}]},"finishReason":"MAX_TOKENS"}]}
				""", MediaType.APPLICATION_JSON));

		LlmResponse response = client.generate(LlmRequest.text(null, "요약해줘"));

		assertThat(response.text()).isEqualTo("앞부분 뒷부분");
		assertThat(response.truncated()).isTrue();
	}

	@Test
	void 한도_초과_429는_AI_RATE_LIMITED이고_재시도하지_않는다() {
		server.expect(requestTo(URL))
			.andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
				.body("{\"error\":{\"code\":429,\"status\":\"RESOURCE_EXHAUSTED\"}}"));

		assertThatThrownBy(() -> client.generate(LlmRequest.text(null, "요약해줘")))
			.isInstanceOfSatisfying(BusinessException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(AiErrorCode.AI_RATE_LIMITED));
		server.verify(); // 요청은 정확히 1번만 나갔다
	}

	@Test
	void 서버_오류와_잘못된_키는_AI_UNAVAILABLE이다() {
		for (HttpStatus status : new HttpStatus[] { HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.SERVICE_UNAVAILABLE,
			HttpStatus.FORBIDDEN, HttpStatus.BAD_REQUEST }) {
			setUp();
			server.expect(requestTo(URL)).andRespond(withStatus(status));

			assertThatThrownBy(() -> client.generate(LlmRequest.text(null, "요약해줘")))
				.as("status=%s", status)
				.isInstanceOfSatisfying(BusinessException.class,
					e -> assertThat(e.getErrorCode()).isEqualTo(AiErrorCode.AI_UNAVAILABLE));
		}
	}

	@Test
	void 프롬프트가_차단되면_AI_UNAVAILABLE이다() {
		server.expect(requestTo(URL))
			.andRespond(withSuccess("{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.generate(LlmRequest.text(null, "요약해줘")))
			.isInstanceOfSatisfying(BusinessException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(AiErrorCode.AI_UNAVAILABLE));
	}

	@Test
	void 후보가_없거나_텍스트가_비어_있으면_AI_UNAVAILABLE이다() {
		server.expect(requestTo(URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
		assertThatThrownBy(() -> client.generate(LlmRequest.text(null, "요약해줘")))
			.isInstanceOfSatisfying(BusinessException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(AiErrorCode.AI_UNAVAILABLE));

		setUp();
		server.expect(requestTo(URL)).andRespond(withSuccess("""
			{"candidates":[{"content":{"parts":[{"text":"  "}]},"finishReason":"STOP"}]}
			""", MediaType.APPLICATION_JSON));
		assertThatThrownBy(() -> client.generate(LlmRequest.text(null, "요약해줘")))
			.isInstanceOfSatisfying(BusinessException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(AiErrorCode.AI_UNAVAILABLE));
	}

	@Test
	void API_키가_없으면_외부_호출_없이_AI_UNAVAILABLE이다() {
		GeminiLlmClient noKey = clientWithKey("");

		assertThatThrownBy(() -> noKey.generate(LlmRequest.text(null, "요약해줘")))
			.isInstanceOfSatisfying(BusinessException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(AiErrorCode.AI_UNAVAILABLE));
		server.verify(); // 기대한 요청이 없으므로 호출이 나갔다면 여기서 실패한다
	}
}
