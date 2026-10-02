package com.withus.workflow.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;

/**
 * WorkflowValidator 입력용 노드 — DB·요청 DTO 어느 쪽과도 무관하게 순수 자료만 담는다.
 * id는 저장된 구조면 step_id 문자열, 저장 전(POST /workflow/validate) 요청이면 임시 key를 그대로 쓴다.
 * config는 DB_SCHEMA 5.2 형식의 config_json을 그대로 Map으로 옮긴 것(condition·templateId·couponId·amount·unit).
 * 설정값은 외부 입력이라 타입이 틀려도 예외 대신 null 로 읽는다 — 검증이 그 null 을 위반으로 보고한다(PR #34 리뷰).
 */
public record WorkflowNode(String id, NodeType nodeType, Map<String, Object> config, String next, String yes,
		String no) {

	public WorkflowNode {
		config = config == null ? Map.of() : config;
	}

	public String condition() {
		return config.get("condition") instanceof String s ? s : null;
	}

	public Long templateId() {
		return asWholeNumber(config.get("templateId"));
	}

	public Long couponId() {
		return asWholeNumber(config.get("couponId"));
	}

	/** 대기 시간 숫자. 정수가 아니거나(1.9) long 범위를 넘으면(4294967297 등 int 로 줄이면 1 이 되는 값 포함) null */
	public Long waitAmount() {
		return asWholeNumber(config.get("amount"));
	}

	public String waitUnit() {
		return config.get("unit") instanceof String s ? s : null;
	}

	/** 소수·범위 초과·숫자가 아닌 값은 null — 잘라서 통과시키지 않는다 */
	private static Long asWholeNumber(Object value) {
		if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
			return ((Number) value).longValue();
		}
		if (value instanceof BigInteger b) {
			return b.bitLength() < 64 ? b.longValue() : null;
		}
		if (value instanceof BigDecimal d) {
			try {
				return d.longValueExact();
			} catch (ArithmeticException e) {
				return null;
			}
		}
		if (value instanceof Double || value instanceof Float) {
			double d = ((Number) value).doubleValue();
			return d == Math.rint(d) && Math.abs(d) < 9.0e18 ? (long) d : null;
		}
		return null;
	}
}
