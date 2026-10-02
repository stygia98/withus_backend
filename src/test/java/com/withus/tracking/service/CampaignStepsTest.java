package com.withus.tracking.service;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.hamcrest.Matchers;
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
 * 워크플로우 단계별 집계 (API_SPEC 10장, PRD F-09 "워크플로우 단계별로 집계").
 * 6.4 예시처럼 경로마다 다른 SEND 노드가 있을 때 단계마다 지표가 따로 나오는지, 기간 필터가 맞게 걸리는지 본다. 로컬 Docker DB, 롤백
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CampaignStepsTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;

	long memberId;
	long segmentId;

	@BeforeEach
	void setUp() {
		memberId = jdbc.queryForObject("""
			INSERT INTO member (email, password, name, role) VALUES (?, 'x', '단계', 'MANAGER') RETURNING member_id
			""", Long.class, "steps-" + UUID.randomUUID() + "@withus.local");
		segmentId = jdbc.queryForObject("INSERT INTO segment (name, created_by) VALUES ('steps', ?) RETURNING segment_id",
			Long.class, memberId);
	}

	private static RequestPostProcessor auth(Role role) {
		return authentication(new UsernamePasswordAuthenticationToken(new AuthMember(1L, role), null,
			List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
	}

	private long workflowCampaign() {
		return jdbc.queryForObject("""
			INSERT INTO campaign (name, type, status, segment_id, trigger_type, created_by)
			VALUES ('가입 환영 여정', 'WORKFLOW', 'ACTIVE', ?, 'CUSTOMER_REGISTERED', ?) RETURNING campaign_id
			""", Long.class, segmentId, memberId);
	}

	private long template(String name) {
		return jdbc.queryForObject("""
			INSERT INTO template (channel, name, subject, body, created_by) VALUES ('EMAIL', ?, '제목', '본문', ?) RETURNING template_id
			""", Long.class, name, memberId);
	}

	private long step(long campaignId, String nodeType, String configJson) {
		return jdbc.queryForObject("""
			INSERT INTO workflow_step (campaign_id, node_type, config_json) VALUES (?, ?, CAST(? AS jsonb)) RETURNING step_id
			""", Long.class, campaignId, nodeType, configJson);
	}

	/** 단계 발송 1건. opened 면 사람 오픈 1건 */
	private void send(long campaignId, long stepId, String status, boolean opened) {
		long customerId = jdbc.queryForObject("""
			INSERT INTO customer (name, email, joined_at, source) VALUES ('단계', ?, DATE '2031-01-01', 'MANUAL') RETURNING customer_id
			""", Long.class, "step-" + UUID.randomUUID() + "@example.com");
		long sendLogId = jdbc.queryForObject("""
			INSERT INTO send_log (campaign_id, step_id, customer_id, recipient, channel, kind, priority, status, sent_at)
			VALUES (?, ?, ?, 'step@withus.local', 'EMAIL', 'CAMPAIGN', 2, ?, now()) RETURNING send_log_id
			""", Long.class, campaignId, stepId, customerId, status);
		if (opened) {
			jdbc.update("INSERT INTO track_event (send_log_id, event_type, bot_yn) VALUES (?, 'OPEN', 'N')", sendLogId);
		}
	}

	@Test
	void SEND_단계마다_지표를_따로_집계하고_다른_노드는_뺀다() throws Exception {
		long campaignId = workflowCampaign();
		long welcome = template("환영 메일");
		long vip = template("VIP 쿠폰 메일");
		long s1 = step(campaignId, "SEND_EMAIL", "{\"templateId\": %d}".formatted(welcome));
		step(campaignId, "WAIT", "{\"amount\": 2, \"unit\": \"DAY\"}");
		step(campaignId, "CONDITION", "{\"condition\": \"EMAIL_CLICKED\"}");
		long s2 = step(campaignId, "SEND_EMAIL", "{\"templateId\": %d, \"couponId\": 5}".formatted(vip));
		long broken = step(campaignId, "SEND_SMS", "{\"templateId\": \"abc\"}");
		send(campaignId, s1, "SENT", true);
		send(campaignId, s1, "SENT", false);
		send(campaignId, s1, "FAILED", false);
		send(campaignId, s2, "SENT", true);

		mvc.perform(get("/api/v1/analytics/campaigns/" + campaignId + "/steps").with(auth(Role.STAFF)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.type").value("WORKFLOW"))
			.andExpect(jsonPath("$.data.steps.length()").value(3))
			.andExpect(jsonPath("$.data.steps[0].stepId").value(s1))
			.andExpect(jsonPath("$.data.steps[0].templateName").value("환영 메일"))
			.andExpect(jsonPath("$.data.steps[0].couponId").value(Matchers.nullValue()))
			.andExpect(jsonPath("$.data.steps[0].kpi.attempted").value(3))
			.andExpect(jsonPath("$.data.steps[0].kpi.sent").value(2))
			.andExpect(jsonPath("$.data.steps[0].kpi.openRate").value(0.5))
			.andExpect(jsonPath("$.data.steps[1].stepId").value(s2))
			.andExpect(jsonPath("$.data.steps[1].couponId").value(5))
			.andExpect(jsonPath("$.data.steps[1].kpi.sent").value(1))
			.andExpect(jsonPath("$.data.steps[1].kpi.openRate").value(1.0))
			// 설정이 잘못된 단계도 목록에는 나오고 템플릿만 비어 있다
			.andExpect(jsonPath("$.data.steps[2].stepId").value(broken))
			.andExpect(jsonPath("$.data.steps[2].templateId").value(Matchers.nullValue()))
			.andExpect(jsonPath("$.data.steps[2].kpi.sent").value(0));
	}

	/** sentAt 시각에 성공한 단계 발송 1건 */
	private void sentAt(long campaignId, long stepId, String sentAt) {
		long customerId = jdbc.queryForObject("""
			INSERT INTO customer (name, email, joined_at, source) VALUES ('기간', ?, DATE '2031-01-01', 'MANUAL') RETURNING customer_id
			""", Long.class, "period-" + UUID.randomUUID() + "@example.com");
		jdbc.update("""
			INSERT INTO send_log (campaign_id, step_id, customer_id, recipient, channel, kind, priority, status, sent_at)
			VALUES (?, ?, ?, 'p@withus.local', 'EMAIL', 'CAMPAIGN', 2, 'SENT', CAST(? AS timestamptz))
			""", campaignId, stepId, customerId, sentAt);
	}

	@Test
	void 기간을_주면_그_기간_발송만_캠페인과_단계에_집계한다() throws Exception {
		// PRD F-09 기간 필터. 한국 날짜 양 끝 포함: 9/30 23:59(한국)는 빠지고 10/7 23:59(한국)는 들어간다
		long campaignId = workflowCampaign();
		long s1 = step(campaignId, "SEND_EMAIL", "{\"templateId\": %d}".formatted(template("기간 메일")));
		sentAt(campaignId, s1, "2031-09-30 23:59:00+09");
		sentAt(campaignId, s1, "2031-10-01 00:00:00+09");
		sentAt(campaignId, s1, "2031-10-07 23:59:00+09");
		sentAt(campaignId, s1, "2031-10-08 00:00:00+09");

		mvc.perform(get("/api/v1/analytics/campaigns/" + campaignId).param("from", "2031-10-01").param("to", "2031-10-07")
				.with(auth(Role.STAFF)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.from").value("2031-10-01"))
			.andExpect(jsonPath("$.data.kpi.sent").value(2));
		mvc.perform(get("/api/v1/analytics/campaigns/" + campaignId + "/steps").param("from", "2031-10-01")
				.param("to", "2031-10-07").with(auth(Role.STAFF)))
			.andExpect(jsonPath("$.data.steps[0].kpi.sent").value(2));
		// 한쪽만 주면 다른 쪽은 제한 없음, 둘 다 없으면 전체
		mvc.perform(get("/api/v1/analytics/campaigns/" + campaignId).param("from", "2031-10-08").with(auth(Role.STAFF)))
			.andExpect(jsonPath("$.data.kpi.sent").value(1))
			.andExpect(jsonPath("$.data.to").value(Matchers.nullValue()));
		mvc.perform(get("/api/v1/analytics/campaigns/" + campaignId).with(auth(Role.STAFF)))
			.andExpect(jsonPath("$.data.kpi.sent").value(4));
	}

	@Test
	void 기간이_잘못되면_캠페인과_단계_모두_400() throws Exception {
		long campaignId = workflowCampaign();

		for (String path : List.of("", "/steps")) {
			String url = "/api/v1/analytics/campaigns/" + campaignId + path;
			// 시작일이 종료일보다 늦음
			mvc.perform(get(url).param("from", "2031-10-08").param("to", "2031-10-01").with(auth(Role.STAFF)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
			// 양 끝을 지정하면 최대 366일 (대시보드와 같은 상한)
			mvc.perform(get(url).param("from", "2030-01-01").param("to", "2031-01-02").with(auth(Role.STAFF)))
				.andExpect(status().isBadRequest());
			mvc.perform(get(url).param("from", "2030-01-01").param("to", "2031-01-01").with(auth(Role.STAFF)))
				.andExpect(status().isOk());
		}
	}

	@Test
	void 기간_검증은_캠페인_존재_확인보다_먼저다() throws Exception {
		for (String path : List.of("", "/steps")) {
			mvc.perform(get("/api/v1/analytics/campaigns/" + Long.MAX_VALUE + path)
					.param("from", "2031-10-08").param("to", "2031-10-01").with(auth(Role.STAFF)))
				.andExpect(status().isBadRequest());
		}
	}

	@Test
	void 실패_건은_적재일_기준이라_이후_처리로_기간이_바뀌지_않는다() throws Exception {
		long campaignId = workflowCampaign();
		long s1 = step(campaignId, "SEND_EMAIL", "{}");
		long customerId = jdbc.queryForObject("""
			INSERT INTO customer (name, email, joined_at, source) VALUES ('실패', ?, DATE '2031-01-01', 'MANUAL') RETURNING customer_id
			""", Long.class, "failed-" + UUID.randomUUID() + "@example.com");
		// 10월 1일에 적재돼 실패했고, 나중(10월 9일)에 다른 처리로 updated_at 만 바뀌었다
		jdbc.update("""
			INSERT INTO send_log (campaign_id, step_id, customer_id, recipient, channel, kind, priority, status,
			                      created_at, updated_at)
			VALUES (?, ?, ?, 'f@withus.local', 'EMAIL', 'CAMPAIGN', 2, 'FAILED',
			        TIMESTAMPTZ '2031-10-01 09:00:00+09', TIMESTAMPTZ '2031-10-09 09:00:00+09')
			""", campaignId, s1, customerId);

		mvc.perform(get("/api/v1/analytics/campaigns/" + campaignId).param("from", "2031-10-01").param("to", "2031-10-01")
				.with(auth(Role.STAFF)))
			.andExpect(jsonPath("$.data.kpi.attempted").value(1));
		mvc.perform(get("/api/v1/analytics/campaigns/" + campaignId + "/steps").param("from", "2031-10-09")
				.param("to", "2031-10-09").with(auth(Role.STAFF)))
			.andExpect(jsonPath("$.data.steps[0].kpi.attempted").value(0));
	}

	@Test
	void 일회성_캠페인은_빈_목록() throws Exception {
		long campaignId = jdbc.queryForObject("""
			INSERT INTO campaign (name, type, status, segment_id, created_by) VALUES ('일회성', 'ONE_TIME', 'ACTIVE', ?, ?) RETURNING campaign_id
			""", Long.class, segmentId, memberId);

		mvc.perform(get("/api/v1/analytics/campaigns/" + campaignId + "/steps").with(auth(Role.MANAGER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.type").value("ONE_TIME"))
			.andExpect(jsonPath("$.data.steps.length()").value(0));
	}

	@Test
	void 없는_캠페인은_404() throws Exception {
		mvc.perform(get("/api/v1/analytics/campaigns/" + Long.MAX_VALUE + "/steps").with(auth(Role.MANAGER)))
			.andExpect(status().isNotFound());
	}
}
