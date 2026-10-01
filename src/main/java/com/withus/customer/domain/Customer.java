package com.withus.customer.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import lombok.Getter;

/** customer 테이블 (DB_SCHEMA 2번). 조회 결과는 MyBatis 가 필드에 직접 넣는다 */
@Getter
public class Customer {

	private Long customerId;
	private String name;
	private String email;
	private String phone;
	private String regionCode;
	private LocalDate birthDate;
	private LocalDate joinedAt;
	private long totalPurchase;
	private String emailConsentYn;
	private OffsetDateTime emailConsentAt;
	private String smsConsentYn;
	private OffsetDateTime smsConsentAt;
	private String dormantYn;
	private String source;
	private OffsetDateTime createdAt;
	private OffsetDateTime updatedAt;

	/** 개별 등록(source=MANUAL)용. 값은 정규화가 끝난 상태여야 한다 */
	public static Customer manual(CustomerFields fields, String emailConsentYn, String smsConsentYn) {
		Customer c = new Customer();
		c.name = fields.name();
		c.email = fields.email();
		c.phone = fields.phone();
		c.regionCode = fields.regionCode();
		c.birthDate = fields.birthDate();
		c.joinedAt = fields.joinedAt();
		c.emailConsentYn = emailConsentYn;
		c.smsConsentYn = smsConsentYn;
		c.source = "MANUAL";
		return c;
	}
}
