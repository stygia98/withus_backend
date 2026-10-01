package com.withus.campaign.domain;

import lombok.Getter;

/** customer 테이블에서 치환자 렌더링에 쓸 값만 뽑은 프로젝션(F-04: name, email, region, totalPurchase) */
@Getter
public class CustomerPlaceholderSource {
	private String name;
	private String email;
	private String regionCode;
	private Long totalPurchase;
}
