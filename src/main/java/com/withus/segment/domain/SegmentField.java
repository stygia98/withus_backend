package com.withus.segment.domain;

import static com.withus.segment.domain.SegmentOperator.BETWEEN;
import static com.withus.segment.domain.SegmentOperator.EQ;
import static com.withus.segment.domain.SegmentOperator.GT;
import static com.withus.segment.domain.SegmentOperator.GTE;
import static com.withus.segment.domain.SegmentOperator.IN;
import static com.withus.segment.domain.SegmentOperator.IN_LAST_DAYS;
import static com.withus.segment.domain.SegmentOperator.LT;
import static com.withus.segment.domain.SegmentOperator.LTE;

import java.util.Set;

/**
 * 조건 필드 화이트리스트 (DB_SCHEMA 5.1). 매퍼 XML 이 필드마다 고정 컬럼명으로 바꾼다
 * 1차 범위 (docs/plans/segment-sql.md 8장). 2차: age, joinedAt BETWEEN, region NE
 */
public enum SegmentField {

	REGION("region", Set.of(EQ, IN)),
	TOTAL_PURCHASE("totalPurchase", Set.of(EQ, GT, GTE, LT, LTE, BETWEEN)),
	JOINED_AT("joinedAt", Set.of(IN_LAST_DAYS)),
	EMAIL_CONSENT("emailConsent", Set.of(EQ)),
	SMS_CONSENT("smsConsent", Set.of(EQ)),
	DORMANT("dormant", Set.of(EQ));

	private final String key;
	private final Set<SegmentOperator> operators;

	SegmentField(String key, Set<SegmentOperator> operators) {
		this.key = key;
		this.operators = operators;
	}

	public String key() {
		return key;
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
