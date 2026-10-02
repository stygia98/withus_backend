package com.withus.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;
import com.withus.auth.domain.Role;
import com.withus.auth.security.AuthMember;

import jakarta.servlet.http.Cookie;

/** 수신동의 변경·이력 (PRD 7장, API_SPEC 3장, CLAUDE.md 6장 10번) */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CustomerConsentApiTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;

	String email;

	@BeforeEach
	void setUp() {
		email = "consent-" + UUID.randomUUID() + "@withus.local";
	}

	@Test
	void 동의를_바꾸면_이력_1건_같은_값이면_변경_없음() throws Exception {
		long id = create("N");

		consent(id, "EMAIL", "Y", null).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.emailConsent").value("Y"))
			.andExpect(jsonPath("$.data.emailConsentAt").exists());
		consent(id, "EMAIL", "Y", null).andExpect(status().isOk());
		consent(id, "EMAIL", "N", null).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.emailConsentAt").doesNotExist());

		call(get("/api/v1/customers/" + id + "/consent-history")).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.length()").value(2))
			.andExpect(jsonPath("$.data[0].before").value("Y"))
			.andExpect(jsonPath("$.data[0].after").value("N"))
			.andExpect(jsonPath("$.data[1].before").value("N"))
			.andExpect(jsonPath("$.data[1].after").value("Y"))
			.andExpect(jsonPath("$.data[1].source").value("ADMIN"));
	}

	@Test
	void 수신거부된_채널은_증빙_없이_Y로_바꿀_수_없고_증빙이_있으면_해제한다() throws Exception {
		jdbc.update("INSERT INTO suppression (channel, value, reason) VALUES ('EMAIL', ?, 'BOUNCE')", email);
		long id = create("Y");

		consent(id, "EMAIL", "Y", "  ").andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.error.code").value("CUSTOMER_CONSENT_EVIDENCE_REQUIRED"));

		consent(id, "EMAIL", "Y", "2026-10-01 서면 재동의").andExpect(status().isOk())
			.andExpect(jsonPath("$.data.emailConsent").value("Y"))
			.andExpect(jsonPath("$.data.suppressedChannels").isEmpty());

		assertThat(jdbc.queryForObject("SELECT count(*) FROM suppression WHERE channel = 'EMAIL' AND value = ?",
			Long.class, email)).isZero();
		call(get("/api/v1/customers/" + id + "/consent-history"))
			.andExpect(jsonPath("$.data[0].note").value("2026-10-01 서면 재동의"));
	}

	@Test
	void 수신거부가_없으면_증빙_없이도_Y로_바꾼다() throws Exception {
		long id = create("N");
		consent(id, "SMS", "Y", null).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.smsConsent").value("Y"))
			.andExpect(jsonPath("$.data.emailConsent").value("N"));
	}

	@Test
	void 잘못된_요청과_권한() throws Exception {
		long id = create("N");
		consent(id, "KAKAO", "Y", null).andExpect(status().isBadRequest());
		consent(id, "EMAIL", "X", null).andExpect(status().isBadRequest());
		consent(Long.MAX_VALUE, "EMAIL", "Y", null).andExpect(status().isNotFound());

		mvc.perform(patch("/api/v1/customers/" + id + "/consent").contentType(MediaType.APPLICATION_JSON)
				.content("{\"channel\":\"EMAIL\",\"consent\":\"Y\"}").with(auth(Role.STAFF)).with(realCsrf()))
			.andExpect(status().isForbidden());
	}

	private long create(String emailConsent) throws Exception {
		String body = """
			{"email":"%s","phone":"010-1234-5678","joinedAt":"2026-09-30","emailConsent":"%s"}
			""".formatted(email, emailConsent);
		String res = call(post("/api/v1/customers").content(body)).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(res, "$.data.customerId")).longValue();
	}

	private ResultActions consent(long id, String channel, String yn, String note) throws Exception {
		String body = """
			{"channel":"%s","consent":"%s","evidenceNote":%s}
			""".formatted(channel, yn, note == null ? "null" : "\"" + note + "\"");
		return call(patch("/api/v1/customers/" + id + "/consent").content(body));
	}

	private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).with(auth(Role.MANAGER)).with(realCsrf()));
	}

	private static org.springframework.test.web.servlet.request.RequestPostProcessor auth(Role role) {
		return authentication(new UsernamePasswordAuthenticationToken(new AuthMember(1L, role), null,
			List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
	}

	/** with(csrf()) 는 공유 컨텍스트의 CSRF 저장소를 바꿔 다른 테스트를 깨뜨리므로 실제 /auth/csrf 쿠키·헤더를 쓴다 (#36) */
	private org.springframework.test.web.servlet.request.RequestPostProcessor realCsrf() throws Exception {
		Cookie xsrf = mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		return request -> {
			request.setCookies(xsrf);
			request.addHeader("X-XSRF-TOKEN", xsrf.getValue());
			return request;
		};
	}
}
