package com.withus.campaign.dto;

/**
 * 메일은 subject·html 을 채운다(html 은 sandbox iframe 으로만 렌더링). SMS 는 subject·html 이 null 이고 text 에 문자 본문(평문,
 * 이스케이프 없음 — 화면에 텍스트로만 출력한다), smsBytes·smsType 에 자동 삽입 문구까지 포함한 바이트 수와 SMS/LMS 판정이 들어간다.
 * defaultValueCount 는 segmentId 가 없으면 null
 */
public record TemplatePreviewResponse(String subject, String html, String text, Integer smsBytes, String smsType,
		DefaultValueCount defaultValueCount) {

	public record DefaultValueCount(long total, long usingDefault) {
	}
}
