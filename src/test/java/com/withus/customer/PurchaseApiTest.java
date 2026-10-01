package com.withus.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.withus.auth.domain.Role;
import com.withus.auth.security.AuthMember;

/** 구매 등록 (PRD F-10 ①, API_SPEC 3장). 로컬 Docker DB, 테스트마다 롤백 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PurchaseApiTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;

	long memberId;
	long customerId;

	@BeforeEach
	void setUp() {
		memberId = jdbc.queryForObject("INSERT INTO member (email, password, name, role) "
			+ "VALUES (?, 'x', '관리자', 'MANAGER') RETURNING member_id", Long.class, "p-" + UUID.randomUUID() + "@withus.local");
		customerId = customer();
	}

	@Test
	void 구매를_등록하면_누적구매액에_더하고_최신순으로_보여준다() throws Exception {
		jdbc.update("UPDATE customer SET total_purchase = 1000 WHERE customer_id = ?", customerId);

		purchase(customerId, "{\"amount\":45000,\"purchasedAt\":\"2026-09-30T14:10:00+09:00\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.amount").value(45000))
			.andExpect(jsonPath("$.data.couponIssueId").doesNotExist())
			.andExpect(jsonPath("$.data.purchasedAt").value("2026-09-30T14:10:00+09:00"));
		purchase(customerId, "{\"amount\":5000}").andExpect(status().isOk());

		assertThat(jdbc.queryForObject("SELECT total_purchase FROM customer WHERE customer_id = ?", Long.class,
			customerId)).isEqualTo(51000L);
		assertThat(jdbc.queryForObject("SELECT created_by FROM purchase WHERE customer_id = ? LIMIT 1", Long.class,
			customerId)).isEqualTo(memberId);
		call(get(url(customerId))).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.length()").value(2))
			.andExpect(jsonPath("$.data[0].amount").value(5000))
			.andExpect(jsonPath("$.data[1].amount").value(45000));
	}

	@Test
	void 쿠폰은_이_고객_발급분_미사용_구매일이_유효기간_안일_때만_쓸_수_있다() throws Exception {
		long coupon = coupon("2026-10-01", "2026-10-31");
		long usable = issue(coupon, customerId, false);
		long others = issue(coupon, customer(), false);
		long used = issue(coupon, customerId, true);

		purchase(customerId, body(others, "2026-10-02")).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.error.code").value("COUPON_NOT_USABLE"));
		purchase(customerId, body(usable, "2026-11-01")).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.error.code").value("COUPON_NOT_USABLE"));
		purchase(customerId, body(used, "2026-10-02")).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("COUPON_ALREADY_USED"));
		purchase(customerId, body(Long.MAX_VALUE, "2026-10-02")).andExpect(status().isUnprocessableContent());

		purchase(customerId, body(usable, "2026-10-31")).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.couponIssueId").value(usable))
			.andExpect(jsonPath("$.data.couponName").value("10월 쿠폰"));
		// 같은 발급 건으로 두 번째 구매는 uq_purchase_coupon_issue 로 막힌다 (트랜잭션이 깨지므로 마지막에 확인)
		purchase(customerId, body(usable, "2026-10-31")).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("COUPON_ALREADY_USED"));
	}

	@Test
	void 금액_검증_권한_삭제된_고객() throws Exception {
		purchase(customerId, "{\"amount\":0}").andExpect(status().isBadRequest());
		purchase(customerId, "{}").andExpect(status().isBadRequest());
		mvc.perform(post(url(customerId)).content("{\"amount\":1000}").contentType(MediaType.APPLICATION_JSON)
			.with(auth(Role.STAFF)).with(csrf())).andExpect(status().isForbidden());

		jdbc.update("UPDATE customer SET deleted_yn = 'Y' WHERE customer_id = ?", customerId);
		purchase(customerId, "{\"amount\":1000}").andExpect(status().isNotFound());
		call(get(url(customerId))).andExpect(status().isNotFound());
	}

	private long customer() {
		return jdbc.queryForObject("INSERT INTO customer (email, joined_at, source) VALUES (?, CURRENT_DATE, 'MANUAL') "
			+ "RETURNING customer_id", Long.class, "buy-" + UUID.randomUUID() + "@withus.local");
	}

	private long coupon(String from, String to) {
		return jdbc.queryForObject("""
			INSERT INTO coupon (name, discount_type, discount_value, valid_from, valid_to)
			VALUES ('10월 쿠폰', 'AMOUNT', 3000, ?::date, ?::date) RETURNING coupon_id
			""", Long.class, from, to);
	}

	private long issue(long couponId, long customerId, boolean used) {
		return jdbc.queryForObject("INSERT INTO coupon_issue (coupon_id, customer_id, used_at) "
			+ "VALUES (?, ?, CASE WHEN ? THEN now() END) RETURNING issue_id", Long.class, couponId, customerId, used);
	}

	private static String body(long couponIssueId, String date) {
		return "{\"amount\":30000,\"couponIssueId\":%d,\"purchasedAt\":\"%sT12:00:00+09:00\"}".formatted(couponIssueId,
			date);
	}

	private static String url(long customerId) {
		return "/api/v1/customers/" + customerId + "/purchases";
	}

	private ResultActions purchase(long customerId, String body) throws Exception {
		return call(post(url(customerId)).content(body));
	}

	private ResultActions call(
		org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).with(auth(Role.MANAGER)).with(csrf()));
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor auth(Role role) {
		return authentication(new UsernamePasswordAuthenticationToken(new AuthMember(memberId, role), null,
			List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
	}
}
