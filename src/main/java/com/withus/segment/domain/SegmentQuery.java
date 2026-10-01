package com.withus.segment.domain;

import java.util.List;

/**
 * 검증·변환을 마친 규칙. 매퍼 XML 의 ruleWhere 가 이 구조만 보고 SQL 을 만든다
 * 값은 모두 #{} 로 바인딩되고, 연결어·필드·연산자는 enum 이라 XML 의 고정 문자열로만 바뀐다
 */
public record SegmentQuery(LogicalOperator operator, List<Group> groups) {

	public record Group(LogicalOperator operator, List<Condition> conditions) {
	}

	/**
	 * 하나의 SQL 비교. op 는 SQL 기준이다 (IN_LAST_DAYS 는 GT 날짜로 바뀌어 들어온다)
	 * value: 단일 값, value2: BETWEEN 상한, values: IN 목록 (IN 은 region 코드만 허용)
	 */
	public record Condition(SegmentField field, SegmentOperator op, Object value, Object value2, List<String> values) {

		public static Condition single(SegmentField field, SegmentOperator op, Object value) {
			return new Condition(field, op, value, null, null);
		}
	}
}
