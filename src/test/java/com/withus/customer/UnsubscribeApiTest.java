package com.withus.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.withus.common.token.UnsubscribeTokens;

/** 수신거부 공개 API (API_SPEC 8장, PRD F-08·8.3). 로그인·CSRF 없이 호출한다. 로컬 Docker DB, 테스트마다 롤백 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UnsubscribeApiTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	UnsubscribeTokens tokens;

	@Test
	void 링크를_열기만_하면_아무것도_바뀌지_않고_연락처를_보여주지_않는다() throws Exception {
		String email = email();
		String phone = phone();
		long id = customer("김민지", email, phone);

		String body = info(token(id)).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.customerName").value("김**"))
			.andExpect(jsonPath("$.data.channels.length()").value(2))
			.andExpect(jsonPath("$.data.unsubscribedChannels.length()").value(0))
			.andReturn().getResponse().getContentAsString();

		assertThat(body).doesNotContain(email, phone);
		assertThat(consent(id)).isEqualTo("YY");
		assertThat(suppressions(email, phone)).isZero();
	}

	@Test
	void 고른_채널만_거부하고_다시_보내도_이력은_한_번만_남는다() throws Exception {
		String email = email();
		String phone = phone();
		long id = customer("김민지", email, phone);
		String token = token(id);

		unsubscribe(token, "SMS").andExpect(status().isOk())
			.andExpect(jsonPath("$.data.channels[0]").value("SMS"))
			.andExpect(jsonPath("$.data.channels.length()").value(1))
			.andExpect(jsonPath("$.data.processedAt").exists());
		assertThat(consent(id)).isEqualTo("YN");
		assertThat(jdbc.queryForObject("SELECT reason FROM suppression WHERE channel = 'SMS' AND value = ?",
			String.class, phone)).isEqualTo("UNSUBSCRIBE");

		unsubscribe(token, "ALL").andExpect(jsonPath("$.data.channels.length()").value(2));
		unsubscribe(token, "ALL").andExpect(status().isOk());

		assertThat(consent(id)).isEqualTo("NN");
		assertThat(suppressions(email, phone)).isEqualTo(2);
		assertThat(jdbc.queryForList("SELECT channel || ':' || before_yn || after_yn || ':' || source "
			+ "FROM consent_history WHERE customer_id = ? AND source = 'UNSUBSCRIBE' ORDER BY channel", String.class,
			id)).containsExactly("EMAIL:YN:UNSUBSCRIBE", "SMS:YN:UNSUBSCRIBE");
		info(token).andExpect(jsonPath("$.data.unsubscribedChannels.length()").value(2));
	}

	@Test
	void 원클릭은_이메일만_거부하고_본문은_보지_않는다() throws Exception {
		long id = customer("김민지", email(), phone());

		mvc.perform(post("/api/v1/unsubscribe/one-click/" + token(id))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED).content("List-Unsubscribe=One-Click"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.channels.length()").value(1))
			.andExpect(jsonPath("$.data.channels[0]").value("EMAIL"));
		assertThat(consent(id)).isEqualTo("NY");
	}

	@Test
	void 휴대폰이_없으면_SMS_는_고를_수_없고_전체는_이메일만_처리한다() throws Exception {
		long id = customer(null, email(), null);

		info(token(id)).andExpect(jsonPath("$.data.customerName").doesNotExist())
			.andExpect(jsonPath("$.data.channels.length()").value(1));
		unsubscribe(token(id), "ALL").andExpect(jsonPath("$.data.channels.length()").value(1))
			.andExpect(jsonPath("$.data.channels[0]").value("EMAIL"));
	}

	@Test
	void 삭제된_고객의_링크도_값으로_거부되고_같은_이메일로_다시_등록한_고객도_N() throws Exception {
		String email = email();
		long deleted = customer("김민지", email, null);
		jdbc.update("UPDATE customer SET deleted_yn = 'Y' WHERE customer_id = ?", deleted);
		long again = customer("김민지", email, null);

		unsubscribe(token(deleted), "EMAIL").andExpect(status().isOk());

		assertThat(suppressions(email, null)).isEqualTo(1);
		assertThat(consent(deleted)).isEqualTo("YY");
		assertThat(consent(again)).isEqualTo("NY");
	}

	@Test
	void 위조_토큰은_사유_구분_없이_400() throws Exception {
		long id = customer("김민지", email(), phone());
		String forged = tokens.issue(1L, id).substring(0, 10) + "x";
		String missing = tokens.issue(1L, Long.MAX_VALUE);

		for (String token : new String[] {forged, missing, "not-a-token"}) {
			info(token).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("UNSUBSCRIBE_INVALID_TOKEN"));
			unsubscribe(token, "ALL").andExpect(status().isBadRequest());
		}
		unsubscribe(token(id), "PUSH").andExpect(status().isBadRequest());
		assertThat(consent(id)).isEqualTo("YY");
	}

	private long customer(String name, String email, String phone) {
		return jdbc.queryForObject("""
			INSERT INTO customer (name, email, phone, joined_at, source,
				email_consent_yn, email_consent_at, sms_consent_yn, sms_consent_at)
			VALUES (?, ?, ?, CURRENT_DATE, 'MANUAL', 'Y', now(), 'Y', now()) RETURNING customer_id
			""", Long.class, name, email, phone);
	}

	private String token(long customerId) {
		return tokens.issue(1L, customerId);
	}

	/** 이메일·SMS 동의를 이어 붙인 값 (예: "YN") */
	private String consent(long customerId) {
		return jdbc.queryForObject("SELECT email_consent_yn || sms_consent_yn FROM customer WHERE customer_id = ?",
			String.class, customerId);
	}

	private int suppressions(String email, String phone) {
		return jdbc.queryForObject("SELECT count(*) FROM suppression WHERE (channel = 'EMAIL' AND value = ?) "
			+ "OR (channel = 'SMS' AND value = ?)", Integer.class, email, phone);
	}

	private static String email() {
		return "unsub-" + UUID.randomUUID() + "@withus.local";
	}

	private static String phone() {
		return "019" + ThreadLocalRandom.current().nextInt(10_000_000, 100_000_000);
	}

	private ResultActions info(String token) throws Exception {
		return mvc.perform(get("/api/v1/public/unsubscribe/" + token));
	}

	private ResultActions unsubscribe(String token, String channel) throws Exception {
		return mvc.perform(post("/api/v1/public/unsubscribe/" + token).contentType(MediaType.APPLICATION_JSON)
			.content("{\"channel\":\"" + channel + "\"}"));
	}
}
