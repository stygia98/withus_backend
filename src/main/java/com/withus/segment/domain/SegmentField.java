package com.withus.segment.domain;

import static com.withus.segment.domain.SegmentOperator.BETWEEN;
import static com.withus.segment.domain.SegmentOperator.EQ;
import static com.withus.segment.domain.SegmentOperator.GT;
import static com.withus.segment.domain.SegmentOperator.GTE;
import static com.withus.segment.domain.SegmentOperator.IN;
import static com.withus.segment.domain.SegmentOperator.IN_LAST_DAYS;
import static com.withus.segment.domain.SegmentOperator.LT;
import static com.withus.segment.domain.SegmentOperator.LTE;
import static com.withus.segment.domain.SegmentOperator.NE;

import java.util.List;

/**
 * 조건 필드 화이트리스트 (DB_SCHEMA 5.1 표 그대로). 매퍼 XML 이 필드마다 고정 컬럼명으로 바꾼다
 * GET /segments/fields 도 이 enum 에서 만든다 (빌더와 서버 화이트리스트가 한 곳에서 나옴)
 */
public enum SegmentField {

	REGION("region", List.of(EQ, NE, IN)),
	/** 만 나이 → birth_date 범위로 바꿔 넣는다 (docs/plans/segment-sql.md 3장) */
	AGE("age", List.of(EQ, GT, GTE, LT, LTE, BETWEEN)),
	JOINED_AT("joinedAt", List.of(BETWEEN, IN_LAST_DAYS)),
	TOTAL_PURCHASE("totalPurchase", List.of(EQ, GT, GTE, LT, LTE, BETWEEN)),
	EMAIL_CONSENT("emailConsent", List.of(EQ)),
	SMS_CONSENT("smsConsent", List.of(EQ)),
	DORMANT("dormant", List.of(EQ));

	private final String key;
	/** 응답 순서를 고정하려고 List 로 둔다 */
	private final List<SegmentOperator> operators;

	SegmentField(String key, List<SegmentOperator> operators) {
		this.key = key;
		this.operators = operators;
	}

	public String key() {
		return key;
	}

	public List<SegmentOperator> operators() {
		return operators;
	}

	public boolean allows(SegmentOperator op) {
		return operators.contains(op);
	}

	/** rule_json 의 field 값으로 찾는다. 없으면 null */
	public static SegmentField fromKey(String key) {
		for (SegmentField f : values()) {
			if (f.key.equals(key)) {
				return f;
			}
		}
		return null;
	}
}
