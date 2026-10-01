package com.withus.customer.domain;

/** 고객 목록 정렬 키 화이트리스트. 매퍼 XML 이 키마다 고정 컬럼으로 바꾼다 (사용자 입력을 컬럼명으로 쓰지 않음) */
public enum CustomerSort {

	CREATED_AT("createdAt"), NAME("name"), JOINED_AT("joinedAt"), TOTAL_PURCHASE("totalPurchase");

	private final String key;

	CustomerSort(String key) {
		this.key = key;
	}

	/** API 키(createdAt 등)로 찾는다. 없으면 null */
	public static CustomerSort fromKey(String key) {
		for (CustomerSort s : values()) {
			if (s.key.equals(key)) {
				return s;
			}
		}
		return null;
	}
}
