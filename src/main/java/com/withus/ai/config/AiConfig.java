package com.withus.ai.config;

import java.net.http.HttpClient;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.withus.ai.service.GeminiLlmClient;
import com.withus.ai.service.LlmClient;
import com.withus.ai.service.MockLlmClient;

/** withus.ai.type 으로 구현을 고른다 — 프로필(환경변수) 전환만으로 운영에 간다 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {

	@Bean
	@ConditionalOnProperty(prefix = "withus.ai", name = "type", havingValue = "gemini", matchIfMissing = true)
	LlmClient geminiLlmClient(AiProperties properties) {
		AiProperties.Gemini gemini = properties.gemini();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
			HttpClient.newBuilder().connectTimeout(gemini.connectTimeout()).build());
		requestFactory.setReadTimeout(gemini.readTimeout());
		RestClient restClient = RestClient.builder().baseUrl(gemini.baseUrl()).requestFactory(requestFactory).build();
		return new GeminiLlmClient(restClient, gemini);
	}

	@Bean
	@ConditionalOnProperty(prefix = "withus.ai", name = "type", havingValue = "mock")
	LlmClient mockLlmClient() {
		return new MockLlmClient();
	}
}
