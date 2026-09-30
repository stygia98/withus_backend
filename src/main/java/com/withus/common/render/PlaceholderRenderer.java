package com.withus.common.render;

import java.util.Map;

/**
 * 구간 간 연결 인터페이스 — 제공: 팀원3(전환), 호출: 팀원2(발송 직전 렌더링·미리보기)
 * 치환자 {{name}}, {{email}}, {{region}}, {{totalPurchase}}, {{couponUrl}} 와 기본값 문법 {{name|고객}} (PRD F-04)
 * 시그니처 확정. 변경은 PL 리뷰로만 한다.
 */
public interface PlaceholderRenderer {

	/**
	 * 고객 값 → 템플릿 기본값 → 시스템 기본값(이름 "고객", 지역 빈 값, 누적구매액 0) 순으로 치환한다.
	 * 빈 문자열이 그대로 나가지 않게 한다.
	 */
	String render(String template, Map<String, String> values);

	/** 기본값으로 대체되는 치환자가 하나라도 있으면 true (미리보기의 "m명은 기본값으로 발송" 집계용) */
	boolean usesDefault(String template, Map<String, String> values);
}
