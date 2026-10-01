package com.withus.customer.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import com.withus.common.domain.Channel;
import com.withus.customer.domain.Customer;

import io.swagger.v3.oas.annotations.media.Schema;

/** 고객 상세 (마스킹 없음) */
public record CustomerResponse(
	long customerId, String name, String email, String phone,
	@Schema(example = "SEOUL") String region,
	LocalDate birthDate, LocalDate joinedAt, long totalPurchase,
	String emailConsent, OffsetDateTime emailConsentAt, String smsConsent, OffsetDateTime smsConsentAt,
	String dormant, String source, OffsetDateTime createdAt,
	@Schema(description = "수신거부 목록(suppression)에 있는 채널. 이 채널은 증빙 없이 동의 Y로 바꿀 수 없다")
	List<Channel> suppressedChannels) {

	public static CustomerResponse of(Customer c, List<Channel> suppressedChannels) {
		return new CustomerResponse(c.getCustomerId(), c.getName(), c.getEmail(), c.getPhone(), c.getRegionCode(),
			c.getBirthDate(), c.getJoinedAt(), c.getTotalPurchase(), c.getEmailConsentYn(), c.getEmailConsentAt(),
			c.getSmsConsentYn(), c.getSmsConsentAt(), c.getDormantYn(), c.getSource(), c.getCreatedAt(),
			suppressedChannels);
	}
}
