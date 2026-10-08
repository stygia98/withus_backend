package com.withus.customer.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import com.withus.customer.domain.Customer;

import io.swagger.v3.oas.annotations.media.Schema;

/** 고객 목록 한 줄. 이메일·휴대폰은 일부 마스킹 (CLAUDE.md 5장) */
public record CustomerListItem(
	long customerId, String name,
	@Schema(example = "ki***@naver.com") String email,
	@Schema(example = "010****5678") String phone,
	@Schema(example = "SEOUL") String region,
	LocalDate joinedAt, long totalPurchase, String emailConsent, String smsConsent, String dormant,
	OffsetDateTime createdAt) {

	public static CustomerListItem of(Customer c) {
		return new CustomerListItem(c.getCustomerId(), c.getName(), maskEmail(c.getEmail()), maskPhone(c.getPhone()),
			c.getRegionCode(), c.getJoinedAt(), c.getTotalPurchase(), c.getEmailConsentYn(), c.getSmsConsentYn(),
			c.getDormantYn(), c.getCreatedAt());
	}

	// TODO(PL 확인): 마스킹 형식은 문서에 없어 제안안 — 이메일 앞 2글자, 휴대폰 앞 3·뒤 4자리만 노출
	static String maskEmail(String email) {
		if (email == null) {
			return null;
		}
		int at = email.indexOf('@');
		if (at < 0) {
			return "***";
		}
		int keep = at > 2 ? 2 : 1;
		return email.substring(0, keep) + "***" + email.substring(at);
	}

	static String maskPhone(String phone) {
		if (phone == null || phone.length() < 8) {
			return phone == null ? null : "***";
		}
		return phone.substring(0, 3) + "*".repeat(phone.length() - 7) + phone.substring(phone.length() - 4);
	}
}
