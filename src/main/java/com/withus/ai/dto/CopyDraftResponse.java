package com.withus.ai.dto;

import java.util.List;

/**
 * AI-01 결과: 제목·본문 3안. 본문은 문단을 빈 줄로 나눈 평문이다(HTML 아님) —
 * 템플릿 에디터에 넣을 때 화면에서 문단으로 바꾼다. 치환자는 {{name|고객}} 만 들어갈 수 있다.
 */
public record CopyDraftResponse(List<Draft> drafts) {

	public record Draft(String subject, String body) {
	}
}
