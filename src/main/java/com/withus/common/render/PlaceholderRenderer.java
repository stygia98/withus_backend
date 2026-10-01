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
	 *
	 * <p>값을 이스케이프하지 않는다. 메일 제목·SMS 같은 <b>텍스트</b>에 쓴다. HTML 본문에는 {@link #renderHtml} 을 쓴다.
	 */
	String render(String template, Map<String, String> values);

	/**
	 * HTML 본문용 치환. {@link #render} 와 같은 규칙이되, <b>고객 값</b>을 HTML 이스케이프해 넣는다.
	 * 이름이 {@code <a href=...>} 같은 HTML 이어도 본문에 태그로 들어가지 않는다.
	 *
	 * <p>템플릿 작성자가 쓴 HTML 과 템플릿 기본값({{name|고객}})은 이미 에디터가 인코딩한 텍스트라 다시 바꾸지 않는다.
	 * values 에는 이스케이프 전의 원본 값을 넘긴다(이미 이스케이프한 값을 넘기면 이중 변환된다).
	 */
	default String renderHtml(String template, Map<String, String> values) {
		return render(template, HtmlEscaper.escapeValues(values));
	}

	/** 기본값으로 대체되는 치환자가 하나라도 있으면 true (미리보기의 "m명은 기본값으로 발송" 집계용) */
	boolean usesDefault(String template, Map<String, String> values);
}
