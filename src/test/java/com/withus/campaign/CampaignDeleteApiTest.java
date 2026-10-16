package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
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
 * 캠페인 삭제 API (API_SPEC 6장) — DRAFT 만 삭제, 그 외 상태·발송 기록이 있으면 409, 워크플로우 노드는 함께 삭제,
 * 시작 실패로 남은 미선점 PENDING 적재분은 먼저 치움, O·M 만 허용. 로컬 DB 를 쓰고 테스트마다 롤백한다
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CampaignDeleteApiTest {

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
	}

	private Cookie login(Role role) throws Exception {
		Member member = new Member();
		member.setEmail("delete-" + UUID.randomUUID() + "@withus.local");
		member.setPassword(passwordEncoder.encode(PASSWORD));
		member.setName("삭제");
		member.setRole(role);
		memberMapper.insert(member);
		return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
				.content("{\"email\":\"" + member.getEmail() + "\",\"password\":\"" + PASSWORD + "\"}"))
			.andReturn().getResponse().getCookie(AuthCookies.ACCESS_TOKEN);
	}

	private ResultActions deleteCampaign(Cookie auth, long campaignId) throws Exception {
		return mvc.perform(delete("/api/v1/campaigns/" + campaignId).cookie(auth, xsrf)
			.header("X-XSRF-TOKEN", xsrf.getValue()));
	}

	private long oneTime(String status) {
		return jdbc.queryForObject("""
			INSERT INTO campaign (name, type, status, segment_id, template_id, created_by)
			VALUES ('삭제 대상', 'ONE_TIME', ?, ?, ?, ?) RETURNING campaign_id
			""", Long.class, status, segmentId, templateId, memberId);
	}

	private boolean exists(long campaignId) {
		return jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM campaign WHERE campaign_id = ?)", Boolean.class,
			campaignId);
	}

	private long customer() {
		return jdbc.queryForObject("""
			INSERT INTO customer (email, joined_at, source, email_consent_yn)
			VALUES (?, now(), 'MANUAL', 'Y') RETURNING customer_id
			""", Long.class, "del-" + UUID.randomUUID() + "@withus.local");
	}

	private void sendLog(long campaignId, long customerId, String status) {
		jdbc.update("""
			INSERT INTO send_log (campaign_id, customer_id, recipient, channel, status, kind, priority)
			VALUES (?, ?, 'a@withus.local', 'EMAIL', ?, 'CAMPAIGN', 2)
			""", campaignId, customerId, status);
	}

	@Test
	void DRAFT_캠페인을_삭제하면_시작_실패로_남은_PENDING_적재분도_함께_사라진다() throws Exception {
		long campaignId = oneTime("DRAFT");
		sendLog(campaignId, customer(), "PENDING");

		deleteCampaign(manager, campaignId).andExpect(status().isOk());

		assertThat(exists(campaignId)).isFalse();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM send_log WHERE campaign_id = ?", Long.class, campaignId))
			.isZero();
	}

	@Test
	void 워크플로우_캠페인을_삭제하면_노드도_함께_삭제된다() throws Exception {
		long campaignId = jdbc.queryForObject("""
			INSERT INTO campaign (name, type, status, segment_id, trigger_type, created_by)
			VALUES ('여정', 'WORKFLOW', 'DRAFT', ?, 'CUSTOMER_REGISTERED', ?) RETURNING campaign_id
			""", Long.class, segmentId, memberId);
		jdbc.update("""
			INSERT INTO workflow_step (campaign_id, node_type, config_json, depth)
			VALUES (?, 'TRIGGER', '{"triggerType":"CUSTOMER_REGISTERED"}'::jsonb, 0)
			""", campaignId);

		deleteCampaign(manager, campaignId).andExpect(status().isOk());

		assertThat(exists(campaignId)).isFalse();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_step WHERE campaign_id = ?", Long.class,
			campaignId)).isZero();
	}

	@Test
	void DRAFT_가_아니면_삭제할_수_없고_캠페인은_그대로다() throws Exception {
		for (String statusName : List.of("SCHEDULED", "ACTIVE", "PAUSED", "COMPLETED")) {
			long campaignId = oneTime(statusName);

			deleteCampaign(manager, campaignId).andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("CAMPAIGN_INVALID_STATUS"));

			assertThat(exists(campaignId)).as("%s 캠페인은 남아 있어야 한다", statusName).isTrue();
		}
	}

	@Test
	void 처리된_발송_기록이_있으면_FK_로_막혀_409_이다() throws Exception {
		long campaignId = oneTime("DRAFT");
		sendLog(campaignId, customer(), "SENT");

		// FK 위반은 PostgreSQL 트랜잭션을 중단시키므로(테스트가 한 트랜잭션) 응답만 확인한다
		deleteCampaign(manager, campaignId).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("CAMPAIGN_INVALID_STATUS"));
	}

	@Test
	void 없는_캠페인은_404_이고_STAFF_는_삭제할_수_없다() throws Exception {
		deleteCampaign(manager, 999_999_999L).andExpect(status().isNotFound());

		long campaignId = oneTime("DRAFT");
		deleteCampaign(login(Role.STAFF), campaignId).andExpect(status().isForbidden());
		assertThat(exists(campaignId)).isTrue();
	}
}
