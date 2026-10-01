package com.withus.segment.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.withus.common.exception.BusinessException;
import com.withus.customer.domain.Region;
import com.withus.segment.domain.LogicalOperator;
import com.withus.segment.domain.SegmentErrorCode;
import com.withus.segment.domain.SegmentField;
import com.withus.segment.domain.SegmentOperator;
import com.withus.segment.domain.SegmentQuery;
import com.withus.segment.domain.SegmentQuery.Condition;
import com.withus.segment.domain.SegmentQuery.Group;

import tools.jackson.databind.JsonNode;

/**
 * 규칙 JSON → SegmentQuery (docs/plans/segment-sql.md 2~4장). DB 없이 단위 테스트한다
 * 저장·미리보기·대상 계산 모두 이 검증을 거친다. 오류 details.path 로 빌더가 문제 조건을 표시한다
 */
public final class SegmentRuleTranslator {

	static final int MAX_CONDITIONS = 10;
	static final int MAX_LAST_DAYS = 3650;

	private SegmentRuleTranslator() {
	}

	/** today 는 상대 조건(IN_LAST_DAYS)의 기준일 */
	public static SegmentQuery translate(JsonNode rule, LocalDate today) {
		if (rule == null || !rule.isObject()) {
			throw invalid("", "규칙은 객체여야 합니다.");
		}
		LogicalOperator operator = logical(rule.get("operator"), "operator");
		JsonNode groups = rule.get("groups");
		if (groups == null || !groups.isArray() || groups.isEmpty()) {
			// Q3: 조건 0개(전체 고객) 규칙은 허용하지 않는다
			throw invalid("groups", "그룹을 하나 이상 추가하세요.");
		}
		int total = 0;
		for (JsonNode g : groups) {
			JsonNode conditions = g.get("conditions");
			total += conditions != null && conditions.isArray() ? conditions.size() : 0;
		}
		if (total > MAX_CONDITIONS) {
			throw new BusinessException(SegmentErrorCode.SEGMENT_TOO_MANY_CONDITIONS);
		}

		List<Group> result = new ArrayList<>();
		for (int gi = 0; gi < groups.size(); gi++) {
			result.add(group(groups.get(gi), "groups[" + gi + "]", today));
		}
		return new SegmentQuery(operator, result);
	}

	private static Group group(JsonNode g, String path, LocalDate today) {
		if (!g.isObject()) {
			throw invalid(path, "그룹은 객체여야 합니다.");
		}
		if (g.has("groups")) {
			throw invalid(path, "그룹은 1단계까지만 만들 수 있습니다.");
		}
		LogicalOperator operator = logical(g.get("operator"), path + ".operator");
		JsonNode conditions = g.get("conditions");
		if (conditions == null || !conditions.isArray() || conditions.isEmpty()) {
			throw invalid(path, "빈 그룹은 만들 수 없습니다.");
		}
		List<Condition> result = new ArrayList<>();
		for (int ci = 0; ci < conditions.size(); ci++) {
			result.add(condition(conditions.get(ci), path + ".conditions[" + ci + "]", today));
		}
		return new Group(operator, result);
	}

	private static Condition condition(JsonNode c, String path, LocalDate today) {
		if (!c.isObject()) {
			throw invalid(path, "조건은 객체여야 합니다.");
		}
		SegmentField field = SegmentField.fromKey(text(c.get("field")));
		if (field == null) {
			throw invalid(path, "알 수 없는 조건 필드입니다: " + text(c.get("field")));
		}
		SegmentOperator op = operator(text(c.get("op")));
		if (op == null || !field.allows(op)) {
			throw invalid(path, field.key() + " 에 " + text(c.get("op")) + " 연산자는 쓸 수 없습니다.");
		}
		JsonNode v = c.get("value");
		String vpath = path + ".value";
		return switch (field) {
			case REGION -> op == SegmentOperator.IN
				? new Condition(field, op, null, null, regions(v, vpath))
				: Condition.single(field, op, region(v, vpath));
			case TOTAL_PURCHASE -> number(field, op, v, vpath, 0, Long.MAX_VALUE);
			// Q2: 오늘 포함 N일 → joined_at > today - N
			case JOINED_AT -> Condition.single(field, SegmentOperator.GT,
				today.minusDays(integer(v, vpath, 1, MAX_LAST_DAYS)));
			case EMAIL_CONSENT, SMS_CONSENT, DORMANT -> Condition.single(field, op, yn(v, vpath));
		};
	}

	private static Condition number(SegmentField field, SegmentOperator op, JsonNode v, String path, long min,
		long max) {
		if (op != SegmentOperator.BETWEEN) {
			return Condition.single(field, op, integer(v, path, min, max));
		}
		if (v == null || !v.isArray() || v.size() != 2) {
			throw invalid(path, "BETWEEN 은 [최소, 최대] 두 값이어야 합니다.");
		}
		long lo = integer(v.get(0), path + "[0]", min, max);
		long hi = integer(v.get(1), path + "[1]", min, max);
		if (lo > hi) {
			throw invalid(path, "최소값이 최대값보다 큽니다.");
		}
		return new Condition(field, op, lo, hi, null);
	}

	private static long integer(JsonNode v, String path, long min, long max) {
		if (v == null || !v.isIntegralNumber() || !v.canConvertToLong()) {
			throw invalid(path, "정수여야 합니다.");
		}
		long n = v.longValue();
		if (n < min || n > max) {
			throw invalid(path, min + " ~ " + max + " 범위여야 합니다.");
		}
		return n;
	}

	private static List<String> regions(JsonNode v, String path) {
		if (v == null || !v.isArray() || v.isEmpty()) {
			throw invalid(path, "지역을 하나 이상 선택하세요.");
		}
		Set<String> codes = new LinkedHashSet<>();
		for (int i = 0; i < v.size(); i++) {
			codes.add(region(v.get(i), path + "[" + i + "]"));
		}
		return List.copyOf(codes);
	}

	/** 저장 형식은 코드(SEOUL 등)만. 시·도명 변환은 고객 입력 정규화에서만 한다 */
	private static String region(JsonNode v, String path) {
		String code = text(v);
		try {
			return Region.valueOf(code).name();
		} catch (IllegalArgumentException | NullPointerException e) {
			throw invalid(path, "알 수 없는 지역 코드입니다: " + code);
		}
	}

	private static String yn(JsonNode v, String path) {
		String s = text(v);
		if (!"Y".equals(s) && !"N".equals(s)) {
			throw invalid(path, "Y 또는 N 이어야 합니다.");
		}
		return s;
	}

	private static LogicalOperator logical(JsonNode v, String path) {
		String s = text(v);
		if ("AND".equals(s)) {
			return LogicalOperator.AND;
		}
		if ("OR".equals(s)) {
			return LogicalOperator.OR;
		}
		throw invalid(path, "AND 또는 OR 이어야 합니다.");
	}

	private static SegmentOperator operator(String s) {
		for (SegmentOperator op : SegmentOperator.values()) {
			if (op.name().equals(s)) {
				return op;
			}
		}
		return null;
	}

	private static String text(JsonNode v) {
		return v != null && v.isString() ? v.stringValue() : null;
	}

	private static BusinessException invalid(String path, String reason) {
		return new BusinessException(SegmentErrorCode.SEGMENT_INVALID_RULE, reason, Map.of("path", path));
	}
}
