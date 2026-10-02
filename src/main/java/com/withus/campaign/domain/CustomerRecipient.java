package com.withus.campaign.domain;

import lombok.Getter;

/** customer 테이블에서 채널별 수신처(이메일 또는 휴대폰)만 뽑은 적재용 프로젝션 */
@Getter
public class CustomerRecipient {
	private Long customerId;
	private String recipient;
}
