package com.withus.ai.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * withus.ai.* (PRD 2.3, 5.3)
 *
 * @param type   gemini = 실제 Gemini 호출, mock = 외부 호출 없이 고정 응답 (local 프로필 기본값)
 * @param gemini Gemini 호출 설정
 */
@ConfigurationProperties("withus.ai")
public record AiProperties(String type, Gemini gemini) {

	/**
	 * @param apiKey         환경변수 GEMINI_API_KEY (비밀값, 커밋 금지)
	 * @param model          무료 등급 모델 코드. 한도·가용 여부는 TECH_STACK 5장 참고
	 * @param baseUrl        REST 기준 주소 (…/v1beta)
	 * @param connectTimeout 연결 제한 시간
	 * @param readTimeout    응답 대기 제한 시간
	 */
	public record Gemini(String apiKey, String model, String baseUrl, Duration connectTimeout, Duration readTimeout) {

		public boolean hasApiKey() {
			return apiKey != null && !apiKey.isBlank();
		}
	}
}
