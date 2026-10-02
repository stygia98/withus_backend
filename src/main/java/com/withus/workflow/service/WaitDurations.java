package com.withus.workflow.service;

import java.time.Duration;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;

/** WAIT 노드 config_json({amount, unit}) → Duration. 엔진(WAIT 도달)과 wake()(발송 결과 도착)가 같이 쓴다 */
final class WaitDurations {

	private WaitDurations() {
	}

	@SuppressWarnings("unchecked")
	static Duration of(String configJson, ObjectMapper objectMapper) {
		Map<String, Object> config = objectMapper.readValue(configJson, Map.class);
		long amount = ((Number) config.get("amount")).longValue();
		String unit = (String) config.get("unit");
		return switch (unit) {
			case "MINUTE" -> Duration.ofMinutes(amount);
			case "HOUR" -> Duration.ofHours(amount);
			case "DAY" -> Duration.ofDays(amount);
			default -> throw new IllegalStateException("알 수 없는 WAIT 단위: " + unit);
		};
	}
}
