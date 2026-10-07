package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.withus.auth.domain.Member;
import com.withus.auth.domain.Role;
import com.withus.auth.mapper.MemberMapper;
import com.withus.auth.security.AuthCookies;

import jakarta.servlet.http.Cookie;

/**
 * 캠페인 복제 API (API_SPEC 6장, 이슈 #62) — 새 DRAFT, 일정 필드 비움, 워크플로우 구조·분기 연결 복사, 인스턴스·발송 이력 미복사,
 * 어떤 상태의 원본이든 복제, 멱등 아님(두 번이면 두 개), O·M 만 허용. 로컬 DB 를 쓰고 테스트마다 롤백한다
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CampaignDuplicateApiTest {

	private static final String PASSWORD = "correct-password";

	@Autowired
	MockMvc mvc;
	@Autowired
	MemberMapper memberMapper;
	@Autowired
	PasswordEncoder passwordEncoder;
	@Autowired
	JdbcTemplate jdbc;

	Cookie xsrf;
	Cookie manager;
	long memberId;
	long segmentId;
	long templateId;
	long couponId;

	@BeforeEach
	void setUp() throws Exception {
		xsrf = mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		manager = login(Role.MANAGER);
		memberId = jdbc.queryForObject("SELECT max(member_id) FROM member", Long.class);
		segmentId = jdbc.queryForObject(
			"INSERT INTO segment (name, created_by) VALUES ('세그먼트', ?) RETURNING segment_id", Long.class, memberId);
		templateId = jdbc.queryForObject("""
			INSERT INTO template (channel, name, subject, body, ad_yn, created_by)
			VALUES ('EMAIL', '템플릿', '제목', '<p>본문</p>', 'N', ?) RETURNING template_id
			""", Long.class, memberId);
		couponId = jdbc.queryForObject("""
			INSERT INTO coupon (name, discount_type, discount_value, valid_from, valid_to)
			VALUES ('쿠폰', 'AMOUNT', 1000, ?, ?) RETURNING coupon_id
			""", Long.class, LocalDate.now(), LocalDate.now().plusDays(30));
	}

	private Cookie login(Role role) throws Exception {
		Member member = new Member();
		member.setEmail("duplicate-" + UUID.randomUUID() + "@withus.local");
		member.setPassword(passwordEncoder.encode(PASSWORD));
		member.setName("복제");
		member.setRole(role);
		memberMapper.insert(member);
		return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
				.content("{\"email\":\"" + member.getEmail() + "\",\"password\":\"" + PASSWORD + "\"}"))
			.andReturn().getResponse().getCookie(AuthCookies.ACCESS_TOKEN);
	}

	private ResultActions duplicate(Cookie auth, long campaignId) throws Exception {
		return mvc.perform(post("/api/v1/campaigns/" + campaignId + "/duplicate").cookie(auth, xsrf)
			.header("X-XSRF-TOKEN", xsrf.getValue()));
	}

	private long duplicateOk(long campaignId) throws Exception {
		String response = duplicate(manager, campaignId).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("DRAFT"))
			.andReturn().getResponse().getContentAsString();
		return Long.parseLong(response.replaceAll("(?s).*\"campaignId\":(\\d+).*", "$1"));
	}

	/** 일정 필드까지 채운 일회성 캠페인 원본 */
	private long oneTime(String name, String status) {
		return jdbc.queryForObject("""
			INSERT INTO campaign (name, type, status, segment_id, template_id, coupon_id,
			                      scheduled_at, started_at, ended_at, created_by)
			VALUES (?, 'ONE_TIME', ?, ?, ?, ?, now() - interval '3 hours', now() - interval '2 hours',
			        CASE WHEN ? = 'COMPLETED' THEN now() - interval '1 hour' END, ?)
			RETURNING campaign_id
			""", Long.class, name, status, segmentId, templateId, couponId, status, memberId);
	}

	private long step(long campaignId, String nodeType, String configJson, int depth) {
		return jdbc.queryForObject("""
			INSERT INTO workflow_step (campaign_id, node_type, config_json, depth)
			VALUES (?, ?, ?::jsonb, ?) RETURNING step_id
			""", Long.class, campaignId, nodeType, configJson, depth);
	}

	private void link(long stepId, Long next, Long yes, Long no) {
		jdbc.update("UPDATE workflow_step SET next_step_id = ?, yes_step_id = ?, no_step_id = ? WHERE step_id = ?",
			next, yes, no, stepId);
	}

	@Test
	void 일회성_복제는_새_DRAFT이고_일정은_비우며_원본은_그대로다() throws Exception {
		long source = oneTime("가을 프로모션", "ACTIVE");

		long copy = duplicateOk(source);

		Map<String, Object> c = jdbc.queryForMap("SELECT * FROM campaign WHERE campaign_id = ?", copy);
		assertThat(c).containsEntry("name", "가을 프로모션 (복사)").containsEntry("status", "DRAFT")
			.containsEntry("type", "ONE_TIME").containsEntry("created_by", memberId)
			.containsEntry("segment_id", segmentId).containsEntry("template_id", templateId)
			.containsEntry("coupon_id", couponId);
		assertThat(c.get("scheduled_at")).as("예약 시각은 복사하지 않는다").isNull();
		assertThat(c.get("started_at")).isNull();
		assertThat(c.get("ended_at")).isNull();
		Map<String, Object> original = jdbc.queryForMap("SELECT * FROM campaign WHERE campaign_id = ?", source);
		assertThat(original).containsEntry("status", "ACTIVE").containsEntry("name", "가을 프로모션");
		assertThat(original.get("started_at")).as("원본의 시작 시각은 그대로").isNotNull();
	}

	@Test
	void 어떤_상태의_원본이든_복제할_수_있다() throws Exception {
		for (String statusName : List.of("DRAFT", "SCHEDULED", "ACTIVE", "PAUSED", "COMPLETED")) {
			long copy = duplicateOk(oneTime("원본-" + statusName, statusName));
			assertThat(jdbc.queryForObject("SELECT status FROM campaign WHERE campaign_id = ?", String.class, copy))
				.as("%s 원본의 복제본", statusName).isEqualTo("DRAFT");
		}
	}

	@Test
	void 워크플로우_복제는_분기_연결까지_새_ID로_이어_붙이고_인스턴스와_발송_이력은_복사하지_않는다() throws Exception {
		long source = jdbc.queryForObject("""
			INSERT INTO campaign (name, type, status, segment_id, trigger_type, started_at, created_by)
			VALUES ('환영 여정', 'WORKFLOW', 'ACTIVE', ?, 'CUSTOMER_REGISTERED', now(), ?) RETURNING campaign_id
			""", Long.class, segmentId, memberId);
		// TRIGGER → SEND_EMAIL → CONDITION ─yes→ WAIT → END_A / ─no→ END_B  (yes·no 두 갈래가 서로 다른 노드로 이어진다)
		long trigger = step(source, "TRIGGER", "{\"triggerType\":\"CUSTOMER_REGISTERED\"}", 0);
		long send = step(source, "SEND_EMAIL", "{\"templateId\":" + templateId + "}", 0);
		long condition = step(source, "CONDITION", "{\"condition\":\"EMAIL_CLICKED\"}", 0);
		long wait = step(source, "WAIT", "{\"amount\":2,\"unit\":\"DAY\"}", 1);
		long endA = step(source, "END", "{}", 1);
		long endB = step(source, "END", "{}", 1);
		link(trigger, send, null, null);
		link(send, condition, null, null);
		link(condition, null, wait, endB);
		link(wait, endA, null, null);

		long customer = jdbc.queryForObject("""
			INSERT INTO customer (email, joined_at, source, email_consent_yn)
			VALUES (?, now(), 'MANUAL', 'Y') RETURNING customer_id
			""", Long.class, "dup-" + UUID.randomUUID() + "@withus.local");
		long instance = jdbc.queryForObject("""
			INSERT INTO workflow_instance (campaign_id, customer_id, current_step_id, status, next_run_at)
			VALUES (?, ?, ?, 'WAITING', now()) RETURNING instance_id
			""", Long.class, source, customer, send);
		jdbc.update("""
			INSERT INTO send_log (campaign_id, instance_id, step_id, customer_id, recipient, channel, status, kind, priority)
			VALUES (?, ?, ?, ?, 'a@withus.local', 'EMAIL', 'SENT', 'CAMPAIGN', 2)
			""", source, instance, send, customer);

		long copy = duplicateOk(source);

		assertThat(jdbc.queryForMap("SELECT type, trigger_type, template_id, coupon_id FROM campaign WHERE campaign_id = ?", copy))
			.containsEntry("type", "WORKFLOW").containsEntry("trigger_type", "CUSTOMER_REGISTERED")
			.containsEntry("template_id", null).containsEntry("coupon_id", null);

		List<Map<String, Object>> original = steps(source);
		List<Map<String, Object>> copied = steps(copy);
		assertThat(copied).hasSameSizeAs(original);
		for (int i = 0; i < original.size(); i++) {
			assertThat(copied.get(i).get("node_type")).as("노드 %d 유형", i).isEqualTo(original.get(i).get("node_type"));
			assertThat(copied.get(i).get("config")).as("노드 %d 설정", i).isEqualTo(original.get(i).get("config"));
			assertThat(copied.get(i).get("depth")).as("노드 %d 깊이", i).isEqualTo(original.get(i).get("depth"));
			// 연결은 같은 위치(순번)의 노드를 가리켜야 한다 — 원본 노드로 새는 연결이 없어야 한다
			for (String column : List.of("next_step_id", "yes_step_id", "no_step_id")) {
				assertThat(indexOf(copied, copied.get(i).get(column))).as("노드 %d %s", i, column)
					.isEqualTo(indexOf(original, original.get(i).get(column)));
			}
		}
		assertThat(count("SELECT count(*) FROM workflow_instance WHERE campaign_id = ?", copy)).as("인스턴스는 복사하지 않는다").isZero();
		assertThat(count("SELECT count(*) FROM send_log WHERE campaign_id = ?", copy)).as("발송 이력은 복사하지 않는다").isZero();
		// 원본은 그대로
		assertThat(steps(source)).isEqualTo(original);
		assertThat(count("SELECT count(*) FROM workflow_instance WHERE campaign_id = ?", source)).isEqualTo(1);
	}

	@Test
	void 이름이_길어도_접미사를_붙인_결과가_100자를_넘지_않는다() throws Exception {
		long copy = duplicateOk(oneTime("가".repeat(100), "COMPLETED"));

		String name = jdbc.queryForObject("SELECT name FROM campaign WHERE campaign_id = ?", String.class, copy);
		assertThat(name).hasSize(100).endsWith(" (복사)").startsWith("가");
	}

	@Test
	void 같은_요청을_두_번_보내면_두_개가_생긴다() throws Exception {
		long source = oneTime("반복 복제", "PAUSED");

		long first = duplicateOk(source);
		long second = duplicateOk(source);

		assertThat(first).isNotEqualTo(second);
		assertThat(count("SELECT count(*) FROM campaign WHERE name = '반복 복제 (복사)'")).isEqualTo(2);
	}

	@Test
	void STAFF는_복제할_수_없다() throws Exception {
		long source = oneTime("권한 확인", "DRAFT");
		Cookie staff = login(Role.STAFF);

		duplicate(staff, source).andExpect(status().isForbidden());

		assertThat(count("SELECT count(*) FROM campaign WHERE name = '권한 확인 (복사)'")).isZero();
	}

	@Test
	void 없는_캠페인은_404다() throws Exception {
		duplicate(manager, 999_999_999L).andExpect(status().isNotFound());
	}

	private List<Map<String, Object>> steps(long campaignId) {
		return new ArrayList<>(jdbc.queryForList("""
			SELECT step_id, node_type, config_json::text AS config, depth, next_step_id, yes_step_id, no_step_id
			FROM workflow_step WHERE campaign_id = ? ORDER BY step_id
			""", campaignId));
	}

	/** 목록에서 step_id 의 위치(순번). null 이면 -1 */
	private static int indexOf(List<Map<String, Object>> steps, Object stepId) {
		if (stepId == null) {
			return -1;
		}
		for (int i = 0; i < steps.size(); i++) {
			if (steps.get(i).get("step_id").equals(stepId)) {
				return i;
			}
		}
		throw new AssertionError("같은 캠페인 안의 노드가 아니다: " + stepId);
	}

	private long count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Long.class, args);
	}
}
