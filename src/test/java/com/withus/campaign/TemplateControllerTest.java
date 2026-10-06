package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * 템플릿 CRUD·복제 API (API_SPEC 5장)
 * 로컬 Docker DB 를 쓰고 테스트마다 롤백한다
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TemplateControllerTest {

	private static final String PASSWORD = "correct-password";

	@Autowired
	MockMvc mvc;
	@Autowired
	MemberMapper memberMapper;
	@Autowired
	PasswordEncoder passwordEncoder;
	@Autowired
	JdbcTemplate jdbcTemplate;

	Cookie xsrf;
	Cookie access;
	long memberId;

	@BeforeEach
	void setUp() throws Exception {
		Member member = new Member();
		member.setEmail("test-" + UUID.randomUUID() + "@withus.local");
		member.setPassword(passwordEncoder.encode(PASSWORD));
		member.setName("테스트");
		member.setRole(Role.MANAGER);
		memberMapper.insert(member);
		memberId = member.getMemberId();

		xsrf = mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		access = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
				.content("{\"email\":\"" + member.getEmail() + "\",\"password\":\"" + PASSWORD + "\"}"))
			.andReturn().getResponse().getCookie(AuthCookies.ACCESS_TOKEN);
	}

	private ResultActions createEmail(String name, String subject, String body) throws Exception {
		return mvc.perform(post("/api/v1/templates").cookie(access, xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"channel":"EMAIL","name":"%s","subject":"%s","body":"%s","adYn":"Y"}
				""".formatted(name, subject, body)));
	}

	@Test
	void 생성_목록_상세_수정_복제_삭제_흐름() throws Exception {
		long templateId = extractTemplateId(createEmail("환영 메일", "{{name|고객}}님 환영합니다", "본문 {{couponUrl}}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.name").value("환영 메일"))
			.andReturn().getResponse().getContentAsString());

		mvc.perform(get("/api/v1/templates").cookie(access).param("channel", "EMAIL"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalElements").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

		mvc.perform(get("/api/v1/templates/" + templateId).cookie(access))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.inUse").value(false));

		mvc.perform(put("/api/v1/templates/" + templateId).cookie(access, xsrf)
				.header("X-XSRF-TOKEN", xsrf.getValue()).contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"name":"환영 메일(수정)","subject":"제목","body":"본문","adYn":"Y"}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.name").value("환영 메일(수정)"));

		mvc.perform(post("/api/v1/templates/" + templateId + "/duplicate").cookie(access, xsrf)
				.header("X-XSRF-TOKEN", xsrf.getValue()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.name").value("환영 메일(수정) 복사본"));

		mvc.perform(delete("/api/v1/templates/" + templateId).cookie(access, xsrf)
				.header("X-XSRF-TOKEN", xsrf.getValue()))
			.andExpect(status().isOk());

		mvc.perform(get("/api/v1/templates/" + templateId).cookie(access))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("TEMPLATE_NOT_FOUND"));
	}

	@Test
	void EMAIL_subject_누락은_400() throws Exception {
		createEmail("제목 없음", "", "본문")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("TEMPLATE_SUBJECT_REQUIRED"));
	}

	@Test
	void 목록_size가_100_초과면_400() throws Exception {
		mvc.perform(get("/api/v1/templates").cookie(access).param("size", "101"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
	}

	@Test
	void 허용되지_않은_치환자는_400() throws Exception {
		createEmail("연락처", "제목", "연락처는 {{phone}} 입니다")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("TEMPLATE_INVALID_PLACEHOLDER"));
	}

	@Test
	void 광고성_템플릿에_광고_표기를_직접_쓰면_400이고_위치를_알려준다() throws Exception {
		createEmail("광고", "(광고) 가을 선물", "본문")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("TEMPLATE_AD_COPY_NOT_ALLOWED"))
			.andExpect(jsonPath("$.error.details.field").value("subject"));
	}

	@Test
	void 사용_중인_템플릿은_수정과_삭제가_409() throws Exception {
		long templateId = extractTemplateId(createEmail("캠페인용", "제목", "본문")
			.andReturn().getResponse().getContentAsString());

		jdbcTemplate.update("INSERT INTO segment (name, created_by) VALUES ('세그먼트', ?)", memberId);
		Long segmentId = jdbcTemplate.queryForObject("SELECT max(segment_id) FROM segment", Long.class);
		jdbcTemplate.update(
			"INSERT INTO campaign (name, type, status, segment_id, template_id, created_by) "
				+ "VALUES ('캠페인', 'ONE_TIME', 'ACTIVE', ?, ?, ?)",
			segmentId, templateId, memberId);

		mvc.perform(put("/api/v1/templates/" + templateId).cookie(access, xsrf)
				.header("X-XSRF-TOKEN", xsrf.getValue()).contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"name":"수정 시도","subject":"제목","body":"본문","adYn":"Y"}
					"""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("TEMPLATE_IN_USE"));

		mvc.perform(delete("/api/v1/templates/" + templateId).cookie(access, xsrf)
				.header("X-XSRF-TOKEN", xsrf.getValue()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("TEMPLATE_IN_USE"));
	}

	/** 응답 JSON 에서 "templateId":123 패턴만 뽑아내는 간단 파서 (별도 ObjectMapper 의존 없이) */
	@Test
	void 테스트_발송은_TEST_큐에_정규화된_수신처로_적재한다() throws Exception {
		long templateId = extractTemplateId(createEmail("테스트 발송용", "제목", "본문").andReturn().getResponse()
			.getContentAsString());

		mvc.perform(post("/api/v1/templates/" + templateId + "/test-send").cookie(access, xsrf)
				.header("X-XSRF-TOKEN", xsrf.getValue()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"recipient\":\"  Me@Withus.LOCAL \"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));

		var row = jdbcTemplate.queryForMap(
			"SELECT kind, priority, status, channel, recipient, customer_id, campaign_id FROM send_log WHERE template_id = ?",
			templateId);
		assertThat(row).containsEntry("kind", "TEST").containsEntry("status", "PENDING")
			.containsEntry("channel", "EMAIL").containsEntry("recipient", "me@withus.local")
			.containsEntry("customer_id", null).containsEntry("campaign_id", null);
		assertThat(((Number) row.get("priority")).intValue()).isEqualTo(1);
	}

	@Test
	void 테스트_발송_수신처_형식이_틀리면_400이고_적재하지_않는다() throws Exception {
		long templateId = extractTemplateId(createEmail("형식 검사용", "제목", "본문").andReturn().getResponse()
			.getContentAsString());

		mvc.perform(post("/api/v1/templates/" + templateId + "/test-send").cookie(access, xsrf)
				.header("X-XSRF-TOKEN", xsrf.getValue()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"recipient\":\"not-an-email\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));

		Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM send_log WHERE template_id = ?",
			Integer.class, templateId);
		assertThat(count).isZero();
	}

	@Test
	void 없는_템플릿의_테스트_발송은_404() throws Exception {
		mvc.perform(post("/api/v1/templates/999999999/test-send").cookie(access, xsrf)
				.header("X-XSRF-TOKEN", xsrf.getValue()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"recipient\":\"me@withus.local\"}"))
			.andExpect(status().isNotFound());
	}

	private long extractTemplateId(String json) {
		var matcher = java.util.regex.Pattern.compile("\"templateId\":(\\d+)").matcher(json);
		assertThat(matcher.find()).as("응답에 templateId 가 있어야 한다: " + json).isTrue();
		return Long.parseLong(matcher.group(1));
	}
}
