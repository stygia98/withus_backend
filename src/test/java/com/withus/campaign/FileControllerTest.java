package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.withus.auth.domain.Member;
import com.withus.auth.domain.Role;
import com.withus.auth.mapper.MemberMapper;
import com.withus.auth.security.AuthCookies;

import jakarta.servlet.http.Cookie;

/**
 * 이미지 업로드 API (API_SPEC 5장)
 * 로컬 Docker DB 를 쓰고 테스트마다 롤백한다 (업로드 파일 자체는 @TempDir 이 아니라 application-local.yml 의
 * withus.storage.local-path 에 남는다 — 로컬 반복 실행 시 쌓이는 건 알려진 제약)
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FileControllerTest {

	private static final String PASSWORD = "correct-password";

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
		member.setEmail("test-" + UUID.randomUUID() + "@withus.local");
		member.setPassword(passwordEncoder.encode(PASSWORD));
		member.setName("테스트");
		member.setRole(Role.MANAGER);
		memberMapper.insert(member);

		xsrf = mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		access = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
				.content("{\"email\":\"" + member.getEmail() + "\",\"password\":\"" + PASSWORD + "\"}"))
			.andReturn().getResponse().getCookie(AuthCookies.ACCESS_TOKEN);
	}

	private MockMultipartHttpServletRequestBuilder authed(MockMultipartHttpServletRequestBuilder builder) {
		return builder.cookie(access, xsrf).header("X-XSRF-TOKEN", xsrf.getValue());
	}

	@Test
	void jpg_업로드하면_공개_URL을_돌려주고_해당_URL이_로그인_없이_200() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "선물.jpg", "image/jpeg", "가짜 이미지 바이트".getBytes());

		String body = mvc.perform(authed(multipart("/api/v1/files/images")).file(file))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.url").exists())
			.andReturn().getResponse().getContentAsString();

		var matcher = Pattern.compile("\"url\":\"[^\"]*(/files/[^\"]+)\"").matcher(body);
		assertThat(matcher.find()).as("응답에 /files/ 로 시작하는 URL 이 있어야 한다: " + body).isTrue();
		String path = matcher.group(1);

		// 인증 쿠키 없이도(= 메일 수신자처럼) 200 이어야 한다
		mvc.perform(get(path)).andExpect(status().isOk());
	}

	@Test
	void 파일_크기가_5MB_초과면_400() throws Exception {
		byte[] big = new byte[6 * 1024 * 1024];
		MockMultipartFile file = new MockMultipartFile("file", "큰파일.jpg", "image/jpeg", big);

		mvc.perform(authed(multipart("/api/v1/files/images")).file(file))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("UPLOAD_FILE_TOO_LARGE"));
	}

	@Test
	void jpg_png_gif가_아니면_400() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "문서.txt", "text/plain", "본문".getBytes());

		mvc.perform(authed(multipart("/api/v1/files/images")).file(file))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("FILE_INVALID_TYPE"));
	}

	@Test
	void 인증_없이_업로드하면_401() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "선물.jpg", "image/jpeg", "x".getBytes());

		mvc.perform(multipart("/api/v1/files/images").file(file))
			.andExpect(status().isUnauthorized());
	}
}
