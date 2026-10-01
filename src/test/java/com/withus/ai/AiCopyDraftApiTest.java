package com.withus.ai;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.withus.auth.domain.Member;
import com.withus.auth.domain.Role;
import com.withus.auth.mapper.MemberMapper;
import com.withus.auth.security.AuthCookies;

import jakarta.servlet.http.Cookie;

/** POST /api/v1/ai/copy-drafts (API_SPEC 11장). Mock LLM 을 써서 Gemini 한도를 쓰지 않는다 */
@SpringBootTest(properties = "withus.ai.type=mock")
@AutoConfigureMockMvc
@Transactional
class AiCopyDraftApiTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	MemberMapper memberMapper;
	@Autowired
	PasswordEncoder passwordEncoder;

	Cookie xsrf;
	Cookie access;

	@BeforeEach
	void setUp() throws Exception {
		Member member = new Member();
		member.setEmail("ai-" + UUID.randomUUID() + "@withus.local");
		member.setPassword(passwordEncoder.encode("correct-password"));
		member.setName("AI");
		member.setRole(Role.STAFF);
		memberMapper.insert(member);
		xsrf = mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		access = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
				.content("{\"email\":\"" + member.getEmail() + "\",\"password\":\"correct-password\"}"))
			.andReturn().getResponse().getCookie(AuthCookies.ACCESS_TOKEN);
	}

	private ResultActions request(String json) throws Exception {
		return mvc.perform(post("/api/v1/ai/copy-drafts").cookie(access, xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
			.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	@Test
	void STAFF도_세_안을_받는다() throws Exception {
		request("""
			{"purpose":"가을 쿠폰 안내","target":"20~30대 우수 고객","tone":"친근하게","keyMessage":"5,000원 할인, 10월 한 달"}
			""")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.drafts.length()").value(3))
			.andExpect(jsonPath("$.data.drafts[0].subject").value("[MOCK 1] 가을 쿠폰 안내"));
	}

	@Test
	void 빈_입력은_400() throws Exception {
		request("""
			{"purpose":"","target":"전체","tone":"정중하게","keyMessage":"안내"}
			""")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
	}

	@Test
	void 이메일이_들어_있으면_AI_PII_DETECTED() throws Exception {
		request("""
			{"purpose":"안내","target":"전체","tone":"정중하게","keyMessage":"문의 help@example.com"}
			""")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("AI_PII_DETECTED"));
	}

	@Test
	void 로그인하지_않으면_401() throws Exception {
		mvc.perform(post("/api/v1/ai/copy-drafts").cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isUnauthorized());
	}
}
