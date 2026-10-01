package com.withus.ai.service;

import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.withus.ai.config.AiProperties;
import com.withus.ai.domain.AiErrorCode;
import com.withus.common.exception.BusinessException;

/**
 * Gemini REST(generateContent) 호출 구현 (PRD 2.3: 백엔드에서만 호출, 키는 환경변수, SDK 없이 RestClient).
 *
 * <ul>
 * <li>429 는 AI_RATE_LIMITED, 그 외 실패(5xx·타임아웃·차단·빈 응답·키 없음)는 AI_UNAVAILABLE 로 바꾼다.</li>
 * <li>한도 초과를 자동 재시도하지 않는다. 무료 등급 한도를 더 쓰게 되고, PRD 도 오류 안내 후 사용자 재시도로 정했다.</li>
 * <li>프롬프트·응답 본문과 API 키는 로그에 남기지 않는다.</li>
 * </ul>
 */
public class GeminiLlmClient implements LlmClient {

	private static final Logger log = LoggerFactory.getLogger(GeminiLlmClient.class);

	private final RestClient restClient;
	private final AiProperties.Gemini properties;

	public GeminiLlmClient(RestClient restClient, AiProperties.Gemini properties) {
		this.restClient = restClient;
		this.properties = properties;
	}

	@Override
	public LlmResponse generate(LlmRequest request) {
		if (!properties.hasApiKey()) {
			log.error("GEMINI_API_KEY 가 설정되지 않아 AI 를 호출할 수 없습니다");
			throw new BusinessException(AiErrorCode.AI_UNAVAILABLE);
		}

		GeminiResponse response;
		try {
			response = restClient.post()
				.uri("/models/{model}:generateContent", properties.model())
				.header("x-goog-api-key", properties.apiKey())
				.contentType(MediaType.APPLICATION_JSON)
				.body(toGeminiRequest(request))
				.retrieve()
				.body(GeminiResponse.class);
		} catch (RestClientResponseException e) {
			int status = e.getStatusCode().value();
			log.warn("Gemini 호출 실패 model={} status={}", properties.model(), status);
			throw new BusinessException(status == 429 ? AiErrorCode.AI_RATE_LIMITED : AiErrorCode.AI_UNAVAILABLE);
		} catch (RestClientException e) {
			// 연결·읽기 시간 초과, 네트워크 오류 등
			log.warn("Gemini 호출 실패 model={} cause={}", properties.model(), e.getClass().getSimpleName());
			throw new BusinessException(AiErrorCode.AI_UNAVAILABLE);
		}
		return toLlmResponse(response);
	}

	private GeminiRequest toGeminiRequest(LlmRequest request) {
		Content system = request.systemInstruction() == null || request.systemInstruction().isBlank() ? null
			: new Content(null, List.of(new Part(request.systemInstruction())));
		GenerationConfig config = new GenerationConfig(request.json() ? "application/json" : null,
			request.temperature(), request.maxOutputTokens());
		return new GeminiRequest(List.of(new Content("user", List.of(new Part(request.prompt())))), system, config);
	}

	private LlmResponse toLlmResponse(GeminiResponse response) {
		if (response == null) {
			throw new BusinessException(AiErrorCode.AI_UNAVAILABLE);
		}
		if (response.promptFeedback() != null && response.promptFeedback().blockReason() != null) {
			log.warn("Gemini 가 프롬프트를 차단했습니다 blockReason={}", response.promptFeedback().blockReason());
			throw new BusinessException(AiErrorCode.AI_UNAVAILABLE);
		}
		Candidate candidate = response.candidates() == null || response.candidates().isEmpty() ? null
			: response.candidates().get(0);
		String text = candidate == null || candidate.content() == null || candidate.content().parts() == null ? ""
			: candidate.content().parts().stream().map(Part::text).filter(t -> t != null).collect(Collectors.joining());
		if (text.isBlank()) {
			log.warn("Gemini 응답이 비어 있습니다 finishReason={}", candidate == null ? null : candidate.finishReason());
			throw new BusinessException(AiErrorCode.AI_UNAVAILABLE);
		}
		return new LlmResponse(text, properties.model(), "MAX_TOKENS".equals(candidate.finishReason()));
	}

	// ---- Gemini REST 요청·응답 형식 (필요한 필드만) ----

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record GeminiRequest(List<Content> contents, Content systemInstruction, GenerationConfig generationConfig) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	record Content(String role, List<Part> parts) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	record Part(String text) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record GenerationConfig(String responseMimeType, Double temperature, Integer maxOutputTokens) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record GeminiResponse(List<Candidate> candidates, PromptFeedback promptFeedback) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Candidate(Content content, String finishReason) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record PromptFeedback(String blockReason) {
	}
}
