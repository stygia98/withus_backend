package com.withus.campaign.dto;

/**
 * html 은 프론트에서 sandbox iframe 으로만 렌더링한다. SMS 는 subject·html 대신 subject=null, html=문자 본문이고
 * smsBytes 에 치환 후 EUC-KR 바이트 수가 들어간다(메일은 null). defaultValueCount 는 segmentId 가 없으면 null
 */
public record TemplatePreviewResponse(String subject, String html, Integer smsBytes,
		DefaultValueCount defaultValueCount) {

	public record DefaultValueCount(long total, long usingDefault) {
	}
}
