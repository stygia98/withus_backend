package com.withus.auth;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.withus.auth.domain.Member;
import com.withus.auth.domain.Role;
import com.withus.auth.mapper.MemberMapper;
import com.withus.auth.security.AuthCookies;

import jakarta.servlet.http.Cookie;

/**
 * 인증·CSRF 흐름 (PRD 10.3 완료 기준: 로그인 유지·토큰 JS 비노출·CSRF 거부·5회 실패 잠금)
 * 로컬 Docker DB 를 쓰고 테스트마다 롤백한다
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthFlowTest {

	private static final String PASSWORD = "correct-password";

	@Autowired
	MockMvc mvc;
	@Autowired
	MemberMapper memberMapper;
	@Autowired
	PasswordEncoder passwordEncoder;

	String email;
	Cookie xsrf;

	@BeforeEach
	void setUp() throws Exception {
		email = "test-" + UUID.randomUUID() + "@withus.local";
		Member member = new Member();
		member.setEmail(email);
		member.setPassword(passwordEncoder.encode(PASSWORD));
		member.setName("테스트");
		member.setRole(Role.MANAGER);
		memberMapper.insert(member);

		xsrf = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk())
			.andReturn().getResponse().getCookie("XSRF-TOKEN");
		assertThat(xsrf).isNotNull();
	}

	@Test
	void 로그인하면_토큰_쿠키가_httpOnly_로_발급된다() throws Exception {
		MockHttpServletResponse res = login(PASSWORD).andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.email").value(email))
			.andReturn().getResponse();

		assertThat(res.getCookie(AuthCookies.ACCESS_TOKEN).isHttpOnly()).isTrue();
		assertThat(res.getCookie(AuthCookies.REFRESH_TOKEN).isHttpOnly()).isTrue();
		assertThat(res.getCookie(AuthCookies.REFRESH_TOKEN).getPath()).isEqualTo("/api/v1/auth");
	}

	@Test
	void CSRF_토큰_없는_변경_요청은_거부된다() throws Exception {
		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(body(PASSWORD)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("AUTH_CSRF_INVALID"));
	}

	@Test
	void 로그인_5회_연속_실패하면_잠긴다() throws Exception {
		for (int i = 0; i < 5; i++) {
			login("wrong").andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"));
		}
		login(PASSWORD).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("AUTH_ACCOUNT_LOCKED"))
			.andExpect(jsonPath("$.error.details.lockedUntil").exists());
	}

	@Test
	void 인증_없이_보호된_API를_부르면_401() throws Exception {
		mvc.perform(get("/api/v1/auth/me"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("AUTH_UNAUTHORIZED"));

		Cookie access = login(PASSWORD).andReturn().getResponse().getCookie(AuthCookies.ACCESS_TOKEN);
		mvc.perform(get("/api/v1/auth/me").cookie(access))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.role").value("MANAGER"));
	}

	@Test
	void 재발급하면_이전_Refresh_토큰은_무효가_된다() throws Exception {
		Cookie oldRefresh = login(PASSWORD).andReturn().getResponse().getCookie(AuthCookies.REFRESH_TOKEN);

		Cookie newRefresh = refresh(oldRefresh).andExpect(status().isOk())
			.andReturn().getResponse().getCookie(AuthCookies.REFRESH_TOKEN);
		assertThat(newRefresh.getValue()).isNotEqualTo(oldRefresh.getValue());

		refresh(oldRefresh).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("AUTH_UNAUTHORIZED"));
	}

	@Test
	void 로그아웃하면_Refresh_토큰으로_재발급할_수_없다() throws Exception {
		MockHttpServletResponse res = login(PASSWORD).andReturn().getResponse();
		Cookie access = res.getCookie(AuthCookies.ACCESS_TOKEN);
		Cookie refresh = res.getCookie(AuthCookies.REFRESH_TOKEN);

		mvc.perform(post("/api/v1/auth/logout").cookie(access, xsrf).header("X-XSRF-TOKEN", xsrf.getValue()))
			.andExpect(status().isOk());

		refresh(refresh).andExpect(status().isUnauthorized());
	}

	private org.springframework.test.web.servlet.ResultActions login(String password) throws Exception {
		return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
			.cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
			.content(body(password)));
	}

	private org.springframework.test.web.servlet.ResultActions refresh(Cookie refreshCookie) throws Exception {
		return mvc.perform(post("/api/v1/auth/refresh").cookie(refreshCookie, xsrf)
			.header("X-XSRF-TOKEN", xsrf.getValue()));
	}

	private String body(String password) {
		return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
	}
}
