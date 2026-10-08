package com.withus.campaign.domain;

import lombok.Getter;

/** customer 테이블에서 치환자 렌더링에 쓸 값만 뽑은 프로젝션(F-04: name, email, region, totalPurchase) */
@Getter
public class CustomerPlaceholderSource {
	private String name;
	private String email;
	private String regionCode;
	private Long totalPurchase;

	// MyBatis 가 쓰는 기본 생성자
	public CustomerPlaceholderSource() {
	}

	public CustomerPlaceholderSource(String name, String email, String regionCode, Long totalPurchase) {
		this.name = name;
		this.email = email;
		this.regionCode = regionCode;
		this.totalPurchase = totalPurchase;
	}
}
