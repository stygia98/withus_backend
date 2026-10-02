package com.withus.workflow.domain;

import java.util.Map;

/**
 * WorkflowValidator 입력용 노드 — DB·요청 DTO 어느 쪽과도 무관하게 순수 자료만 담는다.
 * id는 저장된 구조면 step_id 문자열, 저장 전(POST /workflow/validate) 요청이면 임시 key를 그대로 쓴다.
 * config는 DB_SCHEMA 5.2 형식의 config_json을 그대로 Map으로 옮긴 것(condition·templateId·couponId·amount·unit).
 */
public record WorkflowNode(String id, NodeType nodeType, Map<String, Object> config, String next, String yes,
		String no) {

	public String condition() {
		return (String) config.get("condition");
	}

	public Long templateId() {
		return asLong(config.get("templateId"));
	}

	public Long couponId() {
		return asLong(config.get("couponId"));
	}

	public Integer waitAmount() {
		Long amount = asLong(config.get("amount"));
		return amount == null ? null : amount.intValue();
	}

	public String waitUnit() {
		return (String) config.get("unit");
	}

	private static Long asLong(Object value) {
		return value instanceof Number n ? n.longValue() : null;
	}
}
