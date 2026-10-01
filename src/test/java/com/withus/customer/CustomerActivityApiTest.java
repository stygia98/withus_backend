package com.withus.customer;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.withus.auth.domain.Role;
import com.withus.auth.security.AuthMember;

/** 고객 상세 발송·이벤트·쿠폰 이력 (PRD 4장, API_SPEC 3장). 로컬 Docker DB, 테스트마다 롤백 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CustomerActivityApiTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;

	long memberId;
	long customerId;

	@BeforeEach
	void setUp() {
		memberId = jdbc.queryForObject("INSERT INTO member (email, password, name, role) "
			+ "VALUES (?, 'x', '관리자', 'MANAGER') RETURNING member_id", Long.class, "a-" + UUID.randomUUID() + "@withus.local");
		customerId = customer();
	}

	@Test
	void 발송은_최신순_오픈_클릭은_봇을_빼고_첫_시각() throws Exception {
		long campaign = campaign("10월 프로모션");
		long sent = send(customerId, campaign, "CAMPAIGN", "SENT", "2026-09-01T10:00:00+09:00");
		event(sent, "CLICK", "Y", "2026-09-01T10:00:05+09:00"); // 봇 클릭은 무시
		event(sent, "OPEN", "N", "2026-09-01T11:00:00+09:00");
		event(sent, "CLICK", "N", "2026-09-01T12:00:00+09:00");
		event(sent, "CLICK", "N", "2026-09-02T12:00:00+09:00");
		send(customerId, null, "NOTICE", "SKIPPED", "2026-09-10T10:00:00+09:00");
		send(customer(), campaign, "CAMPAIGN", "SENT", "2026-09-20T10:00:00+09:00"); // 다른 고객

		activity(customerId).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.sends.length()").value(2))
			.andExpect(jsonPath("$.data.sends[0].kind").value("NOTICE"))
			.andExpect(jsonPath("$.data.sends[0].status").value("SKIPPED"))
			.andExpect(jsonPath("$.data.sends[0].campaignName").doesNotExist())
			.andExpect(jsonPath("$.data.sends[0].openedAt").doesNotExist())
			.andExpect(jsonPath("$.data.sends[1].sendLogId").value(sent))
			.andExpect(jsonPath("$.data.sends[1].campaignName").value("10월 프로모션"))
			.andExpect(jsonPath("$.data.sends[1].channel").value("EMAIL"))
			.andExpect(jsonPath("$.data.sends[1].openedAt").value("2026-09-01T11:00:00+09:00"))
			.andExpect(jsonPath("$.data.sends[1].clickedAt").value("2026-09-01T12:00:00+09:00"));
	}

	@Test
	void 쿠폰은_오늘_기준_상태와_함께_최신_발급순() throws Exception {
		long usable = issue(coupon("CURRENT_DATE", "CURRENT_DATE + 30"), false, "2026-09-04T00:00:00+09:00");
		long used = issue(coupon("CURRENT_DATE", "CURRENT_DATE + 30"), true, "2026-09-03T00:00:00+09:00");
		long expired = issue(coupon("CURRENT_DATE - 30", "CURRENT_DATE - 1"), false, "2026-09-02T00:00:00+09:00");
		long notStarted = issue(coupon("CURRENT_DATE + 1", "CURRENT_DATE + 30"), false, "2026-09-01T00:00:00+09:00");

		activity(customerId).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.sends.length()").value(0))
			.andExpect(jsonPath("$.data.coupons[*].issueId").value(org.hamcrest.Matchers.contains(
				(int) usable, (int) used, (int) expired, (int) notStarted)))
			.andExpect(jsonPath("$.data.coupons[*].status").value(org.hamcrest.Matchers.contains(
				"USABLE", "USED", "EXPIRED", "NOT_STARTED")))
			.andExpect(jsonPath("$.data.coupons[0].couponName").value("테스트 쿠폰"))
			.andExpect(jsonPath("$.data.coupons[1].usedAt").exists());
	}

	@Test
	void 삭제된_고객은_404_STAFF_는_403() throws Exception {
		mvc.perform(get(url(customerId)).with(auth(Role.STAFF))).andExpect(status().isForbidden());
		jdbc.update("UPDATE customer SET deleted_yn = 'Y' WHERE customer_id = ?", customerId);
		activity(customerId).andExpect(status().isNotFound());
	}

	private long customer() {
		return jdbc.queryForObject("INSERT INTO customer (email, joined_at, source) VALUES (?, CURRENT_DATE, 'MANUAL') "
			+ "RETURNING customer_id", Long.class, "act-" + UUID.randomUUID() + "@withus.local");
	}

	private long campaign(String name) {
		long segment = jdbc.queryForObject("INSERT INTO segment (name, created_by) VALUES ('대상', ?) RETURNING segment_id",
			Long.class, memberId);
		return jdbc.queryForObject("INSERT INTO campaign (name, type, segment_id, created_by) "
			+ "VALUES (?, 'ONE_TIME', ?, ?) RETURNING campaign_id", Long.class, name, segment, memberId);
	}

	private long send(long customerId, Long campaignId, String kind, String status, String createdAt) {
		return jdbc.queryForObject("""
			INSERT INTO send_log (campaign_id, customer_id, recipient, channel, status, kind, priority, created_at, sent_at)
			VALUES (?, ?, 'r@withus.local', 'EMAIL', ?, ?, 2, ?::timestamptz,
			        CASE WHEN ? = 'SENT' THEN ?::timestamptz END) RETURNING send_log_id
			""", Long.class, campaignId, customerId, status, kind, createdAt, status, createdAt);
	}

	private void event(long sendLogId, String type, String bot, String at) {
		jdbc.update("INSERT INTO track_event (send_log_id, event_type, bot_yn, occurred_at) VALUES (?, ?, ?, ?::timestamptz)",
			sendLogId, type, bot, at);
	}

	/** from·to 는 SQL 날짜 식 (테스트 고정값) */
	private long coupon(String from, String to) {
		return jdbc.queryForObject("INSERT INTO coupon (name, discount_type, discount_value, valid_from, valid_to) "
			+ "VALUES ('테스트 쿠폰', 'AMOUNT', 1000, " + from + ", " + to + ") RETURNING coupon_id", Long.class);
	}

	private long issue(long couponId, boolean used, String issuedAt) {
		return jdbc.queryForObject("INSERT INTO coupon_issue (coupon_id, customer_id, issued_at, used_at) "
			+ "VALUES (?, ?, ?::timestamptz, CASE WHEN ? THEN now() END) RETURNING issue_id", Long.class, couponId,
			customerId, issuedAt, used);
	}

	private static String url(long customerId) {
		return "/api/v1/customers/" + customerId + "/activity";
	}

	private ResultActions activity(long customerId) throws Exception {
		return mvc.perform(get(url(customerId)).with(auth(Role.MANAGER)));
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor auth(Role role) {
		return authentication(new UsernamePasswordAuthenticationToken(new AuthMember(memberId, role), null,
			List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
	}
}
