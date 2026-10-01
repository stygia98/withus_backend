package com.withus.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;
import com.withus.auth.domain.Role;
import com.withus.auth.security.AuthMember;
import com.withus.auth.service.AuthService;
import com.withus.common.exception.BusinessException;

/** 사용자 관리 (PRD 3장 OWNER 전용, API_SPEC 2장). 로컬 Docker DB 를 쓰고 테스트마다 롤백한다 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
// with(csrf()) 는 공유 컨텍스트의 CSRF 저장소를 바꿔 끼워, 이후 실제 /auth/csrf 쿠키를 쓰는 테스트(File·Template·Coupon)를
// 깨뜨린다. 이 클래스는 패키지 순서상 먼저 실행되므로 끝나면 컨텍스트를 버린다 (backend #7 리뷰와 같은 원인)
@DirtiesContext
class MemberApiTest {

	private static final long OWNER_ID = 1L;
	private static final String PASSWORD = "initial-password";

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	AuthService authService;

	String email;

	@BeforeEach
	void setUp() {
		email = "staff-" + UUID.randomUUID() + "@withus.local";
	}

	@Test
	void OWNER_는_사용자를_만들고_목록에서_본다() throws Exception {
		long id = idOf(create(email.toUpperCase(), Role.STAFF)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.email").value(email))
			.andExpect(jsonPath("$.data.role").value("STAFF"))
			.andExpect(jsonPath("$.data.active").value(true))
			.andExpect(jsonPath("$.data.password").doesNotExist()));

		call(get("/api/v1/members"), Role.OWNER).andExpect(status().isOk())
			.andExpect(jsonPath("$.data[?(@.memberId == " + id + ")].email").value(email));
		// 비밀번호는 BCrypt 로 저장한다
		assertThat(jdbc.queryForObject("SELECT password FROM member WHERE member_id = ?", String.class, id))
			.startsWith("$2");

		create(email, Role.MANAGER).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("MEMBER_DUPLICATE_EMAIL"));
	}

	@Test
	void 비활성화하면_로그인할_수_없고_역할을_바꿀_수_있다() throws Exception {
		long id = idOf(create(email, Role.STAFF));
		assertThat(authService.login(email, PASSWORD).member().getMemberId()).isEqualTo(id);

		call(patch("/api/v1/members/" + id).content("{\"role\":\"MANAGER\"}"), Role.OWNER)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.role").value("MANAGER"))
			.andExpect(jsonPath("$.data.active").value(true));
		// 바꾸면 Refresh 가 지워져 다시 로그인해야 한다 (재발급 시 새 역할)
		assertThat(jdbc.queryForObject("SELECT refresh_token_hash FROM member WHERE member_id = ?", String.class, id))
			.isNull();

		call(patch("/api/v1/members/" + id).content("{\"active\":false}"), Role.OWNER)
			.andExpect(jsonPath("$.data.active").value(false))
			.andExpect(jsonPath("$.data.role").value("MANAGER"));
		assertThatThrownBy(() -> authService.login(email, PASSWORD)).isInstanceOf(BusinessException.class)
			.extracting(e -> ((BusinessException) e).getErrorCode().code()).isEqualTo("AUTH_ACCOUNT_INACTIVE");
	}

	@Test
	void 자기_계정은_바꿀_수_없고_OWNER_만_쓸_수_있다() throws Exception {
		call(patch("/api/v1/members/" + OWNER_ID).content("{\"active\":false}"), Role.OWNER)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("MEMBER_SELF_CHANGE"));
		call(patch("/api/v1/members/999999999").content("{\"role\":\"STAFF\"}"), Role.OWNER)
			.andExpect(status().isNotFound());

		call(get("/api/v1/members"), Role.MANAGER).andExpect(status().isForbidden());
		call(post("/api/v1/members").content(body(email, Role.STAFF, PASSWORD)), Role.MANAGER)
			.andExpect(status().isForbidden());
	}

	@Test
	void 입력값을_검증한다() throws Exception {
		call(post("/api/v1/members").content(body(email, Role.STAFF, "short")), Role.OWNER)
			.andExpect(status().isBadRequest());
		call(post("/api/v1/members").content(body("not-email", Role.STAFF, PASSWORD)), Role.OWNER)
			.andExpect(status().isBadRequest());
		call(post("/api/v1/members").content("{\"email\":\"" + email + "\",\"name\":\"a\",\"password\":\""
			+ PASSWORD + "\"}"), Role.OWNER).andExpect(status().isBadRequest());
	}

	private ResultActions create(String email, Role role) throws Exception {
		return call(post("/api/v1/members").content(body(email, role, PASSWORD)), Role.OWNER);
	}

	private static String body(String email, Role role, String password) {
		return "{\"email\":\"%s\",\"name\":\"직원\",\"role\":\"%s\",\"password\":\"%s\"}".formatted(email, role, password);
	}

	private ResultActions call(MockHttpServletRequestBuilder request, Role role) throws Exception {
		var auth = new UsernamePasswordAuthenticationToken(new AuthMember(OWNER_ID, role), null,
			List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
		return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).with(authentication(auth)).with(csrf()));
	}

	private static long idOf(ResultActions result) throws Exception {
		return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.data.memberId"))
			.longValue();
	}
}
