package com.withus.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.withus.auth.domain.Role;
import com.withus.auth.security.AuthMember;

/**
 * AI-03 성과 요약 API (API_SPEC 11장). Mock LLM 으로 Gemini 한도를 쓰지 않는다. 로컬 Docker DB, 롤백
 */
@SpringBootTest(properties = "withus.ai.type=mock")
@AutoConfigureMockMvc
@Transactional
class AiReportApiTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;

	long memberId;
	long segmentId;

	@BeforeEach
	void setUp() {
		memberId = jdbc.queryForObject("""
			INSERT INTO member (email, password, name, role) VALUES (?, 'x', 'AI 리포트', 'MANAGER') RETURNING member_id
			""", Long.class, "report-" + UUID.randomUUID() + "@withus.local");
		segmentId = jdbc.queryForObject("INSERT INTO segment (name, created_by) VALUES ('report', ?) RETURNING segment_id",
			Long.class, memberId);
	}

	private static RequestPostProcessor auth(Role role) {
		return authentication(new UsernamePasswordAuthenticationToken(new AuthMember(1L, role), null,
			List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
	}

	private long campaign(String name) {
		return jdbc.queryForObject("""
			INSERT INTO campaign (name, type, status, segment_id, created_by) VALUES (?, 'ONE_TIME', 'ACTIVE', ?, ?) RETURNING campaign_id
			""", Long.class, name, segmentId, memberId);
	}

	/** 성공 발송 1건 + 사람 오픈 1건 */
	private void sentWithOpen(long campaignId) {
		long customerId = jdbc.queryForObject("""
			INSERT INTO customer (name, email, joined_at, source) VALUES ('리포트', ?, DATE '2031-01-01', 'MANUAL') RETURNING customer_id
			""", Long.class, "rpt-" + UUID.randomUUID() + "@example.com");
		long sendLogId = jdbc.queryForObject("""
			INSERT INTO send_log (campaign_id, customer_id, recipient, channel, kind, priority, status, sent_at)
			VALUES (?, ?, 'rpt@withus.local', 'EMAIL', 'CAMPAIGN', 3, 'SENT', now()) RETURNING send_log_id
			""", Long.class, campaignId, customerId);
		jdbc.update("INSERT INTO track_event (send_log_id, event_type, bot_yn) VALUES (?, 'OPEN', 'N')", sendLogId);
	}

	@Test
	void 요약을_만들면_지표와_함께_저장되고_조회된다() throws Exception {
		long campaignId = campaign("가을 감사 쿠폰 발송");
		sentWithOpen(campaignId);

		mvc.perform(post("/api/v1/ai/reports/campaigns/" + campaignId).with(auth(Role.MANAGER)).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content").value(startsWith("[MOCK] '가을 감사 쿠폰 발송' 캠페인은 1건을 시도해 1건")))
			.andExpect(jsonPath("$.data.model").value("mock"))
			.andExpect(jsonPath("$.data.input.kpi.sent").value(1))
			.andExpect(jsonPath("$.data.input.kpi.openRate").value(1.0));

		mvc.perform(get("/api/v1/ai/reports/campaigns/" + campaignId).with(auth(Role.STAFF)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.campaignId").value(campaignId))
			.andExpect(jsonPath("$.data.content").value(startsWith("[MOCK]")));
	}

	@Test
	void 재생성하면_새_행이_생기고_최근_것을_돌려준다() throws Exception {
		long campaignId = campaign("재생성");
		sentWithOpen(campaignId);

		String first = mvc.perform(post("/api/v1/ai/reports/campaigns/" + campaignId).with(auth(Role.OWNER)).with(csrf()))
			.andReturn().getResponse().getContentAsString();
		mvc.perform(post("/api/v1/ai/reports/campaigns/" + campaignId).with(auth(Role.OWNER)).with(csrf()))
			.andExpect(status().isOk());

		long latestId = jdbc.queryForObject("SELECT max(report_id) FROM ai_report WHERE campaign_id = ?", Long.class,
			campaignId);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_report WHERE campaign_id = ?", Long.class, campaignId))
			.isEqualTo(2L);
		assertThat(first).doesNotContain("\"reportId\":" + latestId + ",");
		mvc.perform(get("/api/v1/ai/reports/campaigns/" + campaignId).with(auth(Role.MANAGER)))
			.andExpect(jsonPath("$.data.reportId").value(latestId));
	}

	@Test
	void 성공_발송이_없으면_LLM_없이_안내_문장을_저장한다() throws Exception {
		long campaignId = campaign("아직 안 보냄");

		mvc.perform(post("/api/v1/ai/reports/campaigns/" + campaignId).with(auth(Role.MANAGER)).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.model").value("none"))
			.andExpect(jsonPath("$.data.content").value("아직 성공한 발송이 없어 요약할 성과가 없습니다."));
	}

	@Test
	void 요약이_없으면_data는_null() throws Exception {
		long campaignId = campaign("요약 없음");

		mvc.perform(get("/api/v1/ai/reports/campaigns/" + campaignId).with(auth(Role.STAFF)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data").value(nullValue()));
	}

	@Test
	void 없는_캠페인은_404() throws Exception {
		mvc.perform(post("/api/v1/ai/reports/campaigns/" + Long.MAX_VALUE).with(auth(Role.MANAGER)).with(csrf()))
			.andExpect(status().isNotFound());
	}

	@Test
	void STAFF는_요약을_만들_수_없다() throws Exception {
		long campaignId = campaign("권한");

		mvc.perform(post("/api/v1/ai/reports/campaigns/" + campaignId).with(auth(Role.STAFF)).with(csrf()))
			.andExpect(status().isForbidden());
	}
}
