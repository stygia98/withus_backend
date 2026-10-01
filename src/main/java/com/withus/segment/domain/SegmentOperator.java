package com.withus.segment.domain;

/** 조건 연산자 (PRD F-03). 필드별 허용 범위는 SegmentField 가 정한다 */
public enum SegmentOperator {
	EQ, NE, GT, GTE, LT, LTE, BETWEEN, IN, IN_LAST_DAYS
}
