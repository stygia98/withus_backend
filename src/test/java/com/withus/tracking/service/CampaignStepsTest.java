package com.withus.tracking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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
import com.withus.tracking.domain.SendStats;
import com.withus.tracking.domain.StepSendStats;
import com.withus.tracking.mapper.DashboardMapper;

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
	@Autowired
	DashboardMapper dashboardMapper;

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

	// ---- 단계별 한 번 집계(sendStatsByStep)가 단계마다의 sendStats 와 같은지 (PR #38 리뷰) ----

	private long newCustomer() {
		return jdbc.queryForObject("""
			INSERT INTO customer (name, email, joined_at, source) VALUES ('혼합', ?, DATE '2031-01-01', 'MANUAL') RETURNING customer_id
			""", Long.class, "mix-" + UUID.randomUUID() + "@example.com");
	}

	/** 단계 발송 1건과 이벤트. events 는 "OPEN:N", "CLICK:Y"(봇) 형식 */
	private long sendRow(long campaignId, long stepId, long customerId, String kind, String status, String... events) {
		long sendLogId = jdbc.queryForObject("""
			INSERT INTO send_log (campaign_id, step_id, customer_id, recipient, channel, kind, priority, status, sent_at)
			VALUES (?, ?, ?, 'mix@withus.local', 'EMAIL', ?, 2, ?, CASE WHEN ? IN ('SENT', 'BOUNCED') THEN now() END)
			RETURNING send_log_id
			""", Long.class, campaignId, stepId, customerId, kind, status, status);
		for (String event : events) {
			String[] typeAndBot = event.split(":");
			jdbc.update("INSERT INTO track_event (send_log_id, event_type, bot_yn) VALUES (?, ?, ?)", sendLogId,
				typeAndBot[0], typeAndBot[1]);
		}
		return sendLogId;
	}

	private void couponUsed(long sendLogId, long customerId) {
		long couponId = jdbc.queryForObject("""
			INSERT INTO coupon (name, discount_type, discount_value, valid_from, valid_to)
			VALUES ('단계 쿠폰', 'AMOUNT', 1000, DATE '2031-01-01', DATE '2031-12-31') RETURNING coupon_id
			""", Long.class);
		jdbc.update("INSERT INTO coupon_issue (coupon_id, customer_id, send_log_id, used_at) VALUES (?, ?, ?, now())",
			couponId, customerId, sendLogId);
	}

	@Test
	void 단계별_한번_집계는_봇_TEST_NOTICE_BOUNCED가_섞여도_단계마다_sendStats와_같다() {
		long campaignId = workflowCampaign();
		long s1 = step(campaignId, "SEND_EMAIL", "{}");
		long s2 = step(campaignId, "SEND_EMAIL", "{}");
		long same = newCustomer();
		// s1: 사람 오픈(2번)·클릭 + 쿠폰 사용, 봇만 있는 성공, 사람 오픈이 있는 BOUNCED, FAILED·SKIPPED
		couponUsed(sendRow(campaignId, s1, same, "CAMPAIGN", "SENT", "OPEN:N", "OPEN:N", "CLICK:N"), same);
		sendRow(campaignId, s1, newCustomer(), "CAMPAIGN", "SENT", "OPEN:Y", "CLICK:Y");
		sendRow(campaignId, s1, newCustomer(), "CAMPAIGN", "BOUNCED", "OPEN:N");
		sendRow(campaignId, s1, newCustomer(), "CAMPAIGN", "FAILED");
		sendRow(campaignId, s1, newCustomer(), "CAMPAIGN", "SKIPPED");
		// TEST·NOTICE 는 사람 이벤트가 있어도 모든 지표에서 빠진다 (CLAUDE.md 6장 8번)
		sendRow(campaignId, s1, newCustomer(), "TEST", "SENT", "OPEN:N", "CLICK:N");
		sendRow(campaignId, s1, newCustomer(), "NOTICE", "SENT", "OPEN:N", "CLICK:N");
		// s2: 성공 1건, 이벤트 없음
		sendRow(campaignId, s2, newCustomer(), "CAMPAIGN", "SENT");

		Map<Long, StepSendStats> byStep = dashboardMapper.sendStatsByStep(campaignId, null, null).stream()
			.collect(Collectors.toMap(StepSendStats::getStepId, Function.identity()));

		for (long stepId : List.of(s1, s2)) {
			SendStats single = dashboardMapper.sendStats(campaignId, stepId, null, null);
			assertThat(byStep.get(stepId)).as("step %d", stepId).usingRecursiveComparison().ignoringFields("stepId")
				.isEqualTo(single);
		}
		StepSendStats first = byStep.get(s1);
		assertThat(first.getAttempted()).isEqualTo(4); // SENT 2 + BOUNCED 1 + FAILED 1 (SKIPPED·TEST·NOTICE 제외)
		assertThat(first.getSent()).isEqualTo(2);
		assertThat(first.getUniqueOpens()).isEqualTo(1); // 오픈 2번은 1명, 봇 오픈·BOUNCED 건 오픈은 제외
		assertThat(first.getUniqueClicks()).isEqualTo(1);
		assertThat(first.getCouponUsed()).isEqualTo(1);
		assertThat(byStep.get(s2).getSent()).isEqualTo(1);
	}

	// ---- 기간 귀속: 반송은 발송 시각, 보류 후 실패는 적재 시각 (PR #38 리뷰) ----

	@Test
	void 반송은_발송일_보류_후_실패는_적재일로_집계된다() throws Exception {
		long campaignId = workflowCampaign();
		long s1 = step(campaignId, "SEND_EMAIL", "{}");
		// 반송: 9월 30일 적재, 10월 1일 발송, 10월 5일 SES 반송으로 BOUNCED
		jdbc.update("""
			INSERT INTO send_log (campaign_id, step_id, customer_id, recipient, channel, kind, priority, status, created_at, sent_at, updated_at)
			VALUES (?, ?, ?, 'b@withus.local', 'EMAIL', 'CAMPAIGN', 2, 'BOUNCED',
			        TIMESTAMPTZ '2031-09-30 20:00:00+09', TIMESTAMPTZ '2031-10-01 08:00:00+09', TIMESTAMPTZ '2031-10-05 10:00:00+09')
			""", campaignId, s1, newCustomer());
		// 보류 후 실패: 10월 2일 21시 적재(광고 시간 밖이라 보류), 10월 3일 재시도 끝에 FAILED. sent_at 없음
		jdbc.update("""
			INSERT INTO send_log (campaign_id, step_id, customer_id, recipient, channel, kind, priority, status, created_at, updated_at)
			VALUES (?, ?, ?, 'h@withus.local', 'EMAIL', 'CAMPAIGN', 2, 'FAILED',
			        TIMESTAMPTZ '2031-10-02 21:00:00+09', TIMESTAMPTZ '2031-10-03 09:00:00+09')
			""", campaignId, s1, newCustomer());

		String url = "/api/v1/analytics/campaigns/" + campaignId + "/steps";
		String[][] dayAndAttempted = { { "2031-09-30", "0" }, { "2031-10-01", "1" }, { "2031-10-02", "1" },
			{ "2031-10-03", "0" }, { "2031-10-05", "0" } };
		for (String[] expected : dayAndAttempted) {
			mvc.perform(get(url).param("from", expected[0]).param("to", expected[0]).with(auth(Role.STAFF)))
				.andExpect(jsonPath("$.data.steps[0].kpi.attempted").value(Integer.parseInt(expected[1])));
		}
	}

	// ---- 366일 상한은 from 만 줘도 적용 (to 생략 = 오늘까지) ----

	@Test
	void from만_주면_오늘까지로_세어_366일_상한을_적용한다() throws Exception {
		long campaignId = workflowCampaign();
		LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
		for (String path : List.of("", "/steps")) {
			String url = "/api/v1/analytics/campaigns/" + campaignId + path;
			mvc.perform(get(url).param("from", today.minusDays(366).toString()).with(auth(Role.STAFF)))
				.andExpect(status().isBadRequest());
			mvc.perform(get(url).param("from", today.minusDays(365).toString()).with(auth(Role.STAFF)))
				.andExpect(status().isOk());
			// from 을 생략하면 캠페인 전체 기간이라 상한이 없다
			mvc.perform(get(url).param("to", today.toString()).with(auth(Role.STAFF))).andExpect(status().isOk());
		}
	}
}
