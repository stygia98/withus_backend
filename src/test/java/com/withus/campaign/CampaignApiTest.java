package com.withus.campaign;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
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
 * 캠페인 CRUD API (API_SPEC 6장, 캠페인 2/4) — 생성(DRAFT)·목록·상세·수정(DRAFT만)·
 * {{couponUrl}} 템플릿의 쿠폰 필수 검증. 로컬 Docker DB, 테스트마다 롤백
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CampaignApiTest {

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
	Cookie access;
	long memberId;
	long segmentId;

	@BeforeEach
	void setUp() throws Exception {
		xsrf = mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		access = login(Role.MANAGER);
		segmentId = jdbc.queryForObject(
			"INSERT INTO segment (name, created_by) VALUES ('세그먼트', ?) RETURNING segment_id", Long.class, memberId);
	}

	private Cookie login(Role role) throws Exception {
		Member member = new Member();
		member.setEmail("campaign-" + UUID.randomUUID() + "@withus.local");
		member.setPassword(passwordEncoder.encode(PASSWORD));
		member.setName("캠페인");
		member.setRole(role);
		memberMapper.insert(member);
		memberId = member.getMemberId();
		return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
				.content("{\"email\":\"" + member.getEmail() + "\",\"password\":\"" + PASSWORD + "\"}"))
			.andReturn().getResponse().getCookie(AuthCookies.ACCESS_TOKEN);
	}

	private ResultActions write(String method, String url, Cookie auth, String json) throws Exception {
		var builder = "PUT".equals(method) ? put(url) : post(url);
		return mvc.perform(builder.cookie(auth, xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
			.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private long template(boolean withCouponUrl) {
		String body = withCouponUrl ? "<p>쿠폰: {{couponUrl}}</p>" : "<p>본문</p>";
		return jdbc.queryForObject("""
			INSERT INTO template (channel, name, subject, body, ad_yn, created_by)
			VALUES ('EMAIL', '템플릿', '제목', ?, 'N', ?) RETURNING template_id
			""", Long.class, body, memberId);
	}

	private long coupon() {
		return jdbc.queryForObject("""
			INSERT INTO coupon (name, discount_type, discount_value, valid_from, valid_to)
			VALUES ('쿠폰', 'AMOUNT', 1000, ?, ?) RETURNING coupon_id
			""", Long.class, LocalDate.now(), LocalDate.now().plusDays(30));
	}

	private String oneTimeBody(long templateId, Long couponId) {
		return """
			{"type":"ONE_TIME","name":"캠페인","segmentId":%d,"templateId":%d,"couponId":%s}
			""".formatted(segmentId, templateId, couponId == null ? "null" : couponId);
	}

	private long createCampaign(String json) throws Exception {
		String response = write("POST", "/api/v1/campaigns", access, json)
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return Long.parseLong(response.replaceAll("(?s).*\"campaignId\":(\\d+).*", "$1"));
	}

	@Test
	void 생성_목록_상세_수정_흐름() throws Exception {
		long templateId = template(false);

		long campaignId = createCampaign(oneTimeBody(templateId, null));

		mvc.perform(get("/api/v1/campaigns").param("type", "ONE_TIME").cookie(access))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content[?(@.campaignId == " + campaignId + ")]").exists());

		mvc.perform(get("/api/v1/campaigns/" + campaignId).cookie(access))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.name").value("캠페인"))
			.andExpect(jsonPath("$.data.status").value("DRAFT"));

		write("PUT", "/api/v1/campaigns/" + campaignId, access,
			"""
			{"name":"캠페인(수정)","segmentId":%d,"templateId":%d}
			""".formatted(segmentId, templateId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.name").value("캠페인(수정)"));
	}

	@Test
	void DRAFT가_아닌_캠페인은_수정할_수_없다() throws Exception {
		long templateId = template(false);
		long campaignId = createCampaign(oneTimeBody(templateId, null));
		jdbc.update("UPDATE campaign SET status = 'ACTIVE' WHERE campaign_id = ?", campaignId);

		write("PUT", "/api/v1/campaigns/" + campaignId, access,
			"""
			{"name":"수정 시도","segmentId":%d,"templateId":%d}
			""".formatted(segmentId, templateId))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("CAMPAIGN_INVALID_STATUS"));
	}

	@Test
	void couponUrl_템플릿에_쿠폰이_없으면_422() throws Exception {
		long templateId = template(true);

		write("POST", "/api/v1/campaigns", access, oneTimeBody(templateId, null))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.error.code").value("CAMPAIGN_COUPON_REQUIRED"));

		long couponId = coupon();
		write("POST", "/api/v1/campaigns", access, oneTimeBody(templateId, couponId))
			.andExpect(status().isOk());
	}

	@Test
	void 워크플로우_캠페인은_템플릿_대신_트리거가_필요하다() throws Exception {
		write("POST", "/api/v1/campaigns", access,
			"""
			{"type":"WORKFLOW","name":"여정","segmentId":%d,"triggerType":"CUSTOMER_REGISTERED"}
			""".formatted(segmentId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.triggerType").value("CUSTOMER_REGISTERED"))
			.andExpect(jsonPath("$.data.templateId").doesNotExist());

		write("POST", "/api/v1/campaigns", access,
			"""
			{"type":"WORKFLOW","name":"여정","segmentId":%d}
			""".formatted(segmentId))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
	}

	@Test
	void STAFF는_조회만_가능하다() throws Exception {
		Cookie staff = login(Role.STAFF);
		long templateId = template(false);

		mvc.perform(get("/api/v1/campaigns").cookie(staff)).andExpect(status().isOk());
		write("POST", "/api/v1/campaigns", staff, oneTimeBody(templateId, null))
			.andExpect(status().isForbidden());
	}
}
