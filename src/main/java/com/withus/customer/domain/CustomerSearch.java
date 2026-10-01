package com.withus.customer.domain;

/**
 * 검증·정규화를 마친 목록 검색 조건. keyword* 는 LIKE 패턴(%·_ 이스케이프 완료)이고 없으면 null
 * keywordPhone 은 검색어에 숫자가 있을 때만 채운다
 */
public record CustomerSearch(String keywordName, String keywordEmail, String keywordPhone, String regionCode,
	String emailConsent, String smsConsent, String dormant, CustomerSort sort, boolean desc, int limit,
	long offset) {
}
