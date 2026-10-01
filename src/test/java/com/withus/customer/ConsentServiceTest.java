package com.withus.customer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.withus.common.domain.Channel;
import com.withus.customer.service.ConsentService;

/** 발송 가능 판정 (PRD 8.2 발송 직전 재확인, 10.1 인터페이스). 로컬 Docker DB, 테스트마다 롤백 */
@SpringBootTest
@Transactional
class ConsentServiceTest {

	@Autowired
	ConsentService consentService;
	@Autowired
	JdbcTemplate jdbc;

	@Test
	void 동의_Y_면_발송_가능_채널은_따로_판정한다() {
		long id = customer("Y", "N", "N", "N");
		assertThat(consentService.isSendable(id, Channel.EMAIL)).isTrue();
		assertThat(consentService.isSendable(id, Channel.SMS)).isFalse();
	}

	@Test
	void 휴면이어도_발송_가능하다() {
		long id = customer("Y", "Y", "Y", "N");
		assertThat(consentService.isSendable(id, Channel.EMAIL)).isTrue();
		assertThat(consentService.isSendable(id, Channel.SMS)).isTrue();
	}

	@Test
	void 수신거부_목록에_있으면_동의_Y_여도_불가_다른_채널은_영향_없음() {
		long id = customer("Y", "Y", "N", "N");
		String phone = jdbc.queryForObject("SELECT phone FROM customer WHERE customer_id = ?", String.class, id);
		jdbc.update("INSERT INTO suppression (channel, value, reason) VALUES ('SMS', ?, 'UNSUBSCRIBE')", phone);

		assertThat(consentService.isSendable(id, Channel.SMS)).isFalse();
		assertThat(consentService.isSendable(id, Channel.EMAIL)).isTrue();
	}

	@Test
	void SMS_동의여도_휴대폰이_없으면_불가() {
		long id = customer("Y", "Y", "N", "N");
		jdbc.update("UPDATE customer SET phone = NULL WHERE customer_id = ?", id);
		assertThat(consentService.isSendable(id, Channel.SMS)).isFalse();
		assertThat(consentService.isSendable(id, Channel.EMAIL)).isTrue();
	}

	@Test
	void 삭제된_고객과_없는_고객은_불가() {
		long id = customer("Y", "Y", "N", "Y");
		assertThat(consentService.isSendable(id, Channel.EMAIL)).isFalse();
		assertThat(consentService.isSendable(Long.MAX_VALUE, Channel.EMAIL)).isFalse();
	}

	private long customer(String email, String sms, String dormant, String deleted) {
		String phone = "010" + (10_000_000 + (int) (Math.random() * 89_999_999));
		return jdbc.queryForObject("""
			INSERT INTO customer (email, phone, joined_at, email_consent_yn, sms_consent_yn, dormant_yn, source, deleted_yn)
			VALUES (?, ?, CURRENT_DATE, ?, ?, ?, 'MANUAL', ?) RETURNING customer_id
			""", Long.class, "send-" + UUID.randomUUID() + "@withus.local", phone, email, sms, dormant, deleted);
	}
}
