package com.withus.tracking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
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
import com.withus.tracking.dto.DailySendResponse;
import com.withus.tracking.dto.QueueStatusResponse;

/**
 * 대시보드·캠페인 성과 집계 (PRD F-09, API_SPEC 10장, 10.3 완료 기준: 테스트 발송은 통계에 잡히지 않는다,
 * 봇 클릭은 클릭률에서 빠진다). 다른 데이터와 섞이지 않도록 2031년 3월에 발송 기록을 만든다. 테스트마다 롤백한다
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DashboardServiceTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	DashboardService service;
	@Autowired
	JdbcTemplate jdbc;

	long campaignId;
	long otherCampaignId;
	long sendA;
	long sendB;
	long sendC;
	long sendTest;

	/** 2031-03-02 10:00 KST */
	static final OffsetDateTime DAY2 = OffsetDateTime.parse("2031-03-02T10:00:00+09:00");

	@BeforeEach
	void setUp() {
		long memberId = jdbc.queryForObject("""
			INSERT INTO member (email, password, name, role) VALUES (?, 'x', '대시보드', 'MANAGER') RETURNING member_id
			""", Long.class, "dash-" + UUID.randomUUID() + "@withus.local");
		long segmentId = jdbc.queryForObject("INSERT INTO segment (name, created_by) VALUES ('dash', ?) RETURNING segment_id",
			Long.class, memberId);
		campaignId = campaign("가을 감사 쿠폰 발송", segmentId, memberId);
		otherCampaignId = campaign("경계 확인용", segmentId, memberId);

		long a = customer("고객A");
		long b = customer("고객B");
		long c = customer("고객C");

		// 시도 5건(SENT 3, FAILED 1, BOUNCED 1), SKIPPED·PENDING 은 시도가 아니다
		sendA = send(campaignId, a, "CAMPAIGN", "SENT", DAY2, DAY2);
		sendB = send(campaignId, b, "CAMPAIGN", "SENT", DAY2, DAY2);
		sendC = send(campaignId, c, "CAMPAIGN", "SENT", DAY2, DAY2);
		send(campaignId, customer("고객D"), "CAMPAIGN", "FAILED", null, DAY2);
		send(campaignId, customer("고객E"), "CAMPAIGN", "BOUNCED", DAY2, DAY2);
		send(campaignId, customer("고객F"), "CAMPAIGN", "SKIPPED", null, DAY2);
		send(campaignId, customer("고객G"), "CAMPAIGN", "PENDING", null, DAY2);
		// TEST 발송은 고객·캠페인 없이 적재되고 통계에서 빠진다
		sendTest = send(null, null, "TEST", "SENT", DAY2, DAY2);

		event(sendA, "OPEN", "N");
		event(sendA, "OPEN", "N"); // 같은 고객의 두 번째 오픈은 한 명으로 센다
		event(sendA, "CLICK", "N");
		event(sendB, "OPEN", "Y"); // 봇 오픈은 빠진다
		event(sendB, "CLICK", "Y"); // 봇 클릭은 빠진다
		event(sendC, "OPEN", "N");
		event(sendTest, "OPEN", "N");
		event(sendTest, "CLICK", "N");
		useCoupon(sendA, a);
	}

	@AfterEach
	void resetClock() {
		service.setClock(Clock.system(DashboardService.SEOUL));
	}

	// ---- 캠페인 성과 ----

	@Test
	void 캠페인_KPI는_봇과_TEST를_빼고_고유_고객으로_센다() throws Exception {
		mvc.perform(get("/api/v1/analytics/campaigns/" + campaignId).with(auth(Role.STAFF)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.campaignId").value(campaignId))
			.andExpect(jsonPath("$.data.name").value("가을 감사 쿠폰 발송"))
			.andExpect(jsonPath("$.data.kpi.attempted").value(5))
			.andExpect(jsonPath("$.data.kpi.sent").value(3))
			.andExpect(jsonPath("$.data.kpi.successRate").value(0.6))
			.andExpect(jsonPath("$.data.kpi.uniqueOpens").value(2))
			.andExpect(jsonPath("$.data.kpi.openRate").value(0.6667))
			.andExpect(jsonPath("$.data.kpi.uniqueClicks").value(1))
			.andExpect(jsonPath("$.data.kpi.clickRate").value(0.3333))
			.andExpect(jsonPath("$.data.kpi.couponUsed").value(1))
			.andExpect(jsonPath("$.data.kpi.conversionRate").value(0.3333))
			.andExpect(jsonPath("$.data.funnel[0].stage").value("ATTEMPTED"))
			.andExpect(jsonPath("$.data.funnel[0].count").value(5))
			.andExpect(jsonPath("$.data.funnel[4].stage").value("CONVERTED"))
			.andExpect(jsonPath("$.data.funnel[4].count").value(1));
	}

	@Test
	void 발송이_없는_캠페인은_모두_0이고_분모_0으로_오류가_나지_않는다() throws Exception {
		mvc.perform(get("/api/v1/analytics/campaigns/" + otherCampaignId).with(auth(Role.MANAGER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.kpi.attempted").value(0))
			.andExpect(jsonPath("$.data.kpi.openRate").value(0.0));
	}

	@Test
	void 없는_캠페인은_404다() throws Exception {
		mvc.perform(get("/api/v1/analytics/campaigns/999999999").with(auth(Role.OWNER)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));
	}

	// ---- 기간 KPI ----

	@Test
	void 기간_KPI는_양_끝_날짜를_포함하고_다음_날_0시는_뺀다() throws Exception {
		long boundaryCustomer = customer("경계");
		send(otherCampaignId, boundaryCustomer, "CAMPAIGN", "SENT", OffsetDateTime.parse("2031-03-01T00:00:00+09:00"), null);
		send(otherCampaignId, customer("범위밖"), "CAMPAIGN", "SENT", OffsetDateTime.parse("2031-03-04T00:00:00+09:00"), null);

		mvc.perform(get("/api/v1/dashboard/summary?from=2031-03-01&to=2031-03-03").with(auth(Role.STAFF)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.from").value("2031-03-01"))
			.andExpect(jsonPath("$.data.to").value("2031-03-03"))
			.andExpect(jsonPath("$.data.kpi.attempted").value(6))
			.andExpect(jsonPath("$.data.kpi.sent").value(4))
			.andExpect(jsonPath("$.data.kpi.uniqueOpens").value(2))
			.andExpect(jsonPath("$.data.kpi.openRate").value(0.5));
	}

	@Test
	void 기간_검증_오류는_400이다() throws Exception {
		mvc.perform(get("/api/v1/dashboard/summary?from=2031-03-05&to=2031-03-01").with(auth(Role.STAFF)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
		mvc.perform(get("/api/v1/dashboard/summary?from=2029-01-01&to=2031-03-01").with(auth(Role.STAFF)))
			.andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/dashboard/summary?from=2031-13-01").with(auth(Role.STAFF)))
			.andExpect(status().isBadRequest());
	}

	@Test
	void 기간을_생략하면_오늘_포함_최근_7일이다() {
		service.setClock(fixedAt("2031-03-03T12:00:00+09:00"));

		var summary = service.summary(null, null);

		assertThat(summary.from()).isEqualTo(LocalDate.parse("2031-02-25"));
		assertThat(summary.to()).isEqualTo(LocalDate.parse("2031-03-03"));
		assertThat(summary.kpi().sent()).isEqualTo(3);
	}

	// ---- 일별 발송 ----

	@Test
	void 일별_발송은_발송_없는_날도_0으로_채우고_TEST는_뺀다() {
		send(otherCampaignId, customer("첫날"), "CAMPAIGN", "SENT", OffsetDateTime.parse("2031-03-01T00:00:00+09:00"), null);
		service.setClock(fixedAt("2031-03-03T12:00:00+09:00"));

		List<DailySendResponse> days = service.dailySends(3);

		assertThat(days).containsExactly(
			new DailySendResponse(LocalDate.parse("2031-03-01"), 1),
			new DailySendResponse(LocalDate.parse("2031-03-02"), 3),
			new DailySendResponse(LocalDate.parse("2031-03-03"), 0));
	}

	@Test
	void 일별_발송_days_범위_검증() throws Exception {
		mvc.perform(get("/api/v1/dashboard/daily-sends?days=0").with(auth(Role.STAFF))).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/dashboard/daily-sends?days=91").with(auth(Role.STAFF))).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/dashboard/daily-sends").with(auth(Role.STAFF)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.length()").value(14));
	}

	// ---- 발송 큐 ----

	@Test
	void 큐는_대기_재시도_발송중을_나눠_세고_예상_종료_시각을_계산한다() {
		service.setClock(fixedAt("2031-03-03T12:00:00+09:00"));
		// 기준값은 JdbcTemplate 으로 센다. 같은 트랜잭션에서 MyBatis 로 두 번 조회하면 1차 캐시가 첫 결과를 돌려준다
		long pendingBefore = countQueue("status = 'PENDING' AND attempt_count = 0");
		long retryingBefore = countQueue("status = 'PENDING' AND attempt_count > 0");
		long sendingBefore = countQueue("status = 'SENDING'");

		long d = customer("큐");
		jdbc.update("INSERT INTO send_log (campaign_id, customer_id, recipient, channel, kind, priority, status, attempt_count) "
			+ "VALUES (?, ?, 'q@withus.local', 'EMAIL', 'CAMPAIGN', 3, 'PENDING', 0), (?, ?, 'q@withus.local', 'EMAIL', 'TEST', 1, 'PENDING', 0), "
			+ "(?, ?, 'q@withus.local', 'EMAIL', 'CAMPAIGN', 3, 'PENDING', 2), (?, ?, 'q@withus.local', 'EMAIL', 'CAMPAIGN', 3, 'SENDING', 1)",
			otherCampaignId, d, null, null, otherCampaignId, customer("큐2"), otherCampaignId, customer("큐3"));
		QueueStatusResponse after = service.queue();

		// TEST 발송도 같은 큐를 쓰므로 대기 건수에 들어간다
		assertThat(after.pending() - pendingBefore).isEqualTo(2);
		assertThat(after.retrying() - retryingBefore).isEqualTo(1);
		assertThat(after.sending() - sendingBefore).isEqualTo(1);
		long remaining = after.pending() + after.retrying() + after.sending();
		assertThat(after.expectedEndAt()).isEqualTo(
			OffsetDateTime.parse("2031-03-03T12:00:00+09:00").plusSeconds((long) Math.ceil(remaining / after.ratePerSecond())));
		assertThat(after.adSendWindowOpen()).isTrue();
	}

	@Test
	void 광고성_발송_가능_시간은_08시_포함_20시50분_미포함이다() {
		assertThat(service.isAdSendWindowOpen(LocalTime.of(7, 59, 59))).isFalse();
		assertThat(service.isAdSendWindowOpen(LocalTime.of(8, 0))).isTrue();
		assertThat(service.isAdSendWindowOpen(LocalTime.of(20, 49, 59))).isTrue();
		assertThat(service.isAdSendWindowOpen(LocalTime.of(20, 50))).isFalse();
	}

	// ---- 최근 이벤트 ----

	@Test
	void 최근_이벤트는_봇과_TEST를_빼고_최신순이며_after로_새것만_받는다() throws Exception {
		long before = jdbc.queryForObject("SELECT COALESCE(min(event_id), 1) - 1 FROM track_event WHERE send_log_id IN (?, ?, ?, ?)",
			Long.class, sendA, sendB, sendC, sendTest);
		long newest = jdbc.queryForObject("SELECT max(event_id) FROM track_event WHERE send_log_id IN (?, ?)", Long.class,
			sendA, sendC);

		mvc.perform(get("/api/v1/dashboard/events?after=" + before).with(auth(Role.STAFF)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.events.length()").value(4)) // A: OPEN·OPEN·CLICK, C: OPEN
			.andExpect(jsonPath("$.data.events[0].eventId").value(newest))
			.andExpect(jsonPath("$.data.events[0].campaignName").value("가을 감사 쿠폰 발송"))
			.andExpect(jsonPath("$.data.events[0].customerName").value("고객C"))
			.andExpect(jsonPath("$.data.lastEventId").value(newest));

		mvc.perform(get("/api/v1/dashboard/events?after=" + newest).with(auth(Role.STAFF)))
			.andExpect(jsonPath("$.data.events.length()").value(0))
			.andExpect(jsonPath("$.data.lastEventId").value(newest));
	}

	@Test
	void 최근_이벤트_size_범위_검증() throws Exception {
		mvc.perform(get("/api/v1/dashboard/events?size=101").with(auth(Role.STAFF))).andExpect(status().isBadRequest());
	}

	// ---- 권한 ----

	@Test
	void 로그인하지_않으면_401이다() throws Exception {
		mvc.perform(get("/api/v1/dashboard/summary")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/analytics/campaigns/" + campaignId)).andExpect(status().isUnauthorized());
	}

	// ---- 픽스처 ----

	private static RequestPostProcessor auth(Role role) {
		return authentication(new UsernamePasswordAuthenticationToken(new AuthMember(1L, role), null,
			List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
	}

	private long countQueue(String condition) {
		return jdbc.queryForObject("SELECT count(*) FROM send_log WHERE " + condition, Long.class);
	}

	private static Clock fixedAt(String isoOffsetDateTime) {
		return Clock.fixed(OffsetDateTime.parse(isoOffsetDateTime).toInstant(), DashboardService.SEOUL);
	}

	private long campaign(String name, long segmentId, long memberId) {
		return jdbc.queryForObject("""
			INSERT INTO campaign (name, type, status, segment_id, created_by) VALUES (?, 'ONE_TIME', 'ACTIVE', ?, ?) RETURNING campaign_id
			""", Long.class, name, segmentId, memberId);
	}

	private long customer(String name) {
		return jdbc.queryForObject("""
			INSERT INTO customer (name, email, joined_at, source) VALUES (?, ?, DATE '2031-01-01', 'MANUAL') RETURNING customer_id
			""", Long.class, name, "dash-" + UUID.randomUUID() + "@example.com");
	}

	private long send(Long campaign, Long customer, String kind, String status, OffsetDateTime sentAt, OffsetDateTime updatedAt) {
		return jdbc.queryForObject("""
			INSERT INTO send_log (campaign_id, customer_id, recipient, channel, kind, priority, status, sent_at, updated_at)
			VALUES (?, ?, 'dash@withus.local', 'EMAIL', ?, ?, ?, ?, COALESCE(?, now())) RETURNING send_log_id
			""", Long.class, campaign, customer, kind, "TEST".equals(kind) ? 1 : 3, status, sentAt, updatedAt);
	}

	private void event(long sendLogId, String type, String botYn) {
		jdbc.update("INSERT INTO track_event (send_log_id, event_type, bot_yn) VALUES (?, ?, ?)", sendLogId, type, botYn);
	}

	private void useCoupon(long sendLogId, long customerId) {
		long couponId = jdbc.queryForObject("""
			INSERT INTO coupon (name, discount_type, discount_value, valid_from, valid_to)
			VALUES ('대시보드 쿠폰', 'AMOUNT', 1000, DATE '2031-01-01', DATE '2031-12-31') RETURNING coupon_id
			""", Long.class);
		jdbc.update("INSERT INTO coupon_issue (coupon_id, customer_id, send_log_id, used_at) VALUES (?, ?, ?, now())",
			couponId, customerId, sendLogId);
	}
}
