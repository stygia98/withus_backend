package com.withus.common;

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
import org.springframework.transaction.annotation.Transactional;

import com.withus.auth.domain.Member;
import com.withus.auth.domain.Role;
import com.withus.auth.mapper.MemberMapper;
import com.withus.auth.security.AuthCookies;

import jakarta.servlet.http.Cookie;

/**
 * 요청 형식 오류는 500 이 아니라 400 COMMON_INVALID_INPUT 이다 (API_SPEC 1.3, backend #37).
 * 필수 @RequestParam 이 있는 AI-02, JSON 본문을 받는 AI-01 로 실제 경로를 확인한다. Mock LLM 이라 Gemini 를 부르지 않는다
 */
@SpringBootTest(properties = "withus.ai.type=mock")
@AutoConfigureMockMvc
@Transactional
class RequestBindingErrorApiTest {

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
		member.setEmail("binding-" + UUID.randomUUID() + "@withus.local");
		member.setPassword(passwordEncoder.encode("correct-password"));
		member.setName("요청오류");
		member.setRole(Role.MANAGER);
		memberMapper.insert(member);
		xsrf = mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		access = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
				.content("{\"email\":\"" + member.getEmail() + "\",\"password\":\"correct-password\"}"))
			.andReturn().getResponse().getCookie(AuthCookies.ACCESS_TOKEN);
	}

	@Test
	void 필수_파라미터가_없으면_400이고_메시지에_파라미터_이름만_넣는다() throws Exception {
		mvc.perform(get("/api/v1/ai/send-time-recommendations").param("adYn", "Y").cookie(access))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"))
			.andExpect(jsonPath("$.error.message").value("필수 요청 값이 없습니다: targetCount"));
	}

	@Test
	void 파라미터_타입이_틀리면_400이고_입력값을_돌려주지_않는다() throws Exception {
		mvc.perform(get("/api/v1/ai/send-time-recommendations").param("targetCount", "<script>").param("adYn", "Y")
				.cookie(access))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"))
			.andExpect(jsonPath("$.error.message").value("요청 값이 올바르지 않습니다."));
	}

	@Test
	void 본문_JSON_형식이_틀리면_400() throws Exception {
		mvc.perform(post("/api/v1/ai/copy-drafts").cookie(access, xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
				.contentType(MediaType.APPLICATION_JSON).content("{\"purpose\": "))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
	}
}
