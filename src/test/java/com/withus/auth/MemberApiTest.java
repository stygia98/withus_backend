package com.withus.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
	void 이름과_이메일을_수정하고_이메일은_정규화와_중복을_검사한다() throws Exception {
		long id = idOf(create(email, Role.STAFF));
		String other = "other-" + UUID.randomUUID() + "@withus.local";
		create(other, Role.STAFF).andExpect(status().isOk());
		String changed = "changed-" + UUID.randomUUID() + "@withus.local";

		call(patch("/api/v1/members/" + id).content("{\"name\":\" 스태프2 \",\"email\":\"" + changed.toUpperCase() + "\"}"),
			Role.OWNER)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.name").value("스태프2"))
			.andExpect(jsonPath("$.data.email").value(changed))
			.andExpect(jsonPath("$.data.role").value("STAFF"));
		// 이름·이메일만 바꾸면 로그인을 끊지 않는다 (토큰은 member_id 기준)
		assertThat(authService.login(changed, PASSWORD).member().getMemberId()).isEqualTo(id);
		assertThat(refreshHashOf(id)).isNotNull();
		call(patch("/api/v1/members/" + id).content("{\"name\":\"이름만\"}"), Role.OWNER).andExpect(status().isOk());
		assertThat(refreshHashOf(id)).isNotNull();

		call(patch("/api/v1/members/" + id).content("{\"email\":\"" + other + "\"}"), Role.OWNER)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("MEMBER_DUPLICATE_EMAIL"));
		call(patch("/api/v1/members/" + id).content("{\"name\":\"   \"}"), Role.OWNER).andExpect(status().isBadRequest());
		call(patch("/api/v1/members/" + id).content("{\"email\":\"not-email\"}"), Role.OWNER)
			.andExpect(status().isBadRequest());
	}

	@Test
	void 자기_계정도_이름과_이메일은_바꾸지만_역할은_못_바꾼다() throws Exception {
		String mine = "owner-" + UUID.randomUUID() + "@withus.local";
		call(patch("/api/v1/members/" + OWNER_ID).content("{\"name\":\"김위드\",\"email\":\"" + mine + "\"}"), Role.OWNER)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.name").value("김위드"));
		call(patch("/api/v1/members/" + OWNER_ID).content("{\"name\":\"김위드\",\"role\":\"STAFF\"}"), Role.OWNER)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("MEMBER_SELF_CHANGE"));
	}

	@Test
	void 삭제하면_목록과_로그인에서_빠지고_같은_이메일로_다시_만들_수_있다() throws Exception {
		long id = idOf(create(email, Role.STAFF));
		authService.login(email, PASSWORD);
		// 삭제해도 그 계정이 만든 기록(created_by)은 남는다
		long segmentId = jdbc.queryForObject("INSERT INTO segment (name, created_by) VALUES ('삭제 전 생성', ?) RETURNING segment_id",
			Long.class, id);

		call(delete("/api/v1/members/" + id), Role.OWNER).andExpect(status().isOk());

		call(get("/api/v1/members"), Role.OWNER)
			.andExpect(jsonPath("$.data[?(@.memberId == " + id + ")]").isEmpty());
		call(get("/api/v1/members/deleted"), Role.OWNER)
			.andExpect(jsonPath("$.data[?(@.memberId == " + id + ")].email").value(email))
			.andExpect(jsonPath("$.data[?(@.memberId == " + id + ")].deletedAt").isNotEmpty());
		assertThat(refreshHashOf(id)).isNull();
		assertThatThrownBy(() -> authService.login(email, PASSWORD)).isInstanceOf(BusinessException.class)
			.extracting(e -> ((BusinessException) e).getErrorCode().code()).isEqualTo("AUTH_INVALID_CREDENTIALS");
		assertThat(jdbc.queryForObject("SELECT created_by FROM segment WHERE segment_id = ?", Long.class, segmentId))
			.isEqualTo(id);
		// 삭제한 계정은 수정·재삭제할 수 없다
		call(patch("/api/v1/members/" + id).content("{\"name\":\"x\"}"), Role.OWNER).andExpect(status().isNotFound());
		call(delete("/api/v1/members/" + id), Role.OWNER).andExpect(status().isNotFound());

		create(email, Role.MANAGER).andExpect(status().isOk());
	}

	@Test
	void 복구하면_예전_비밀번호로_로그인되고_같은_이메일이_다시_생겼으면_409() throws Exception {
		long id = idOf(create(email, Role.STAFF));
		call(delete("/api/v1/members/" + id), Role.OWNER).andExpect(status().isOk());

		call(post("/api/v1/members/" + id + "/restore"), Role.OWNER)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.deletedAt").doesNotExist());
		assertThat(authService.login(email, PASSWORD).member().getMemberId()).isEqualTo(id);
		call(post("/api/v1/members/" + id + "/restore"), Role.OWNER).andExpect(status().isNotFound());

		call(delete("/api/v1/members/" + id), Role.OWNER).andExpect(status().isOk());
		create(email, Role.STAFF).andExpect(status().isOk());
		call(post("/api/v1/members/" + id + "/restore"), Role.OWNER)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("MEMBER_DUPLICATE_EMAIL"));
	}

	@Test
	void 자기_계정은_삭제할_수_없고_삭제와_복구는_OWNER_만() throws Exception {
		call(delete("/api/v1/members/" + OWNER_ID), Role.OWNER)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("MEMBER_SELF_DELETE"));
		call(delete("/api/v1/members/999999999"), Role.OWNER).andExpect(status().isNotFound());

		long id = idOf(create(email, Role.STAFF));
		call(delete("/api/v1/members/" + id), Role.MANAGER).andExpect(status().isForbidden());
		call(get("/api/v1/members/deleted"), Role.MANAGER).andExpect(status().isForbidden());
		call(post("/api/v1/members/" + id + "/restore"), Role.MANAGER).andExpect(status().isForbidden());
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

	@Test
	void OWNER_가_비밀번호를_재설정하면_새_비밀번호로만_로그인된다() throws Exception {
		long id = idOf(create(email, Role.STAFF));
		authService.login(email, PASSWORD);
		assertThat(refreshHashOf(id)).isNotNull();

		call(post("/api/v1/members/" + id + "/password").content("{\"newPassword\":\"brand-new-pass\"}"), Role.OWNER)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data").doesNotExist());

		// 기존 로그인은 끊기고, 비밀번호는 BCrypt 해시로만 남는다
		assertThat(refreshHashOf(id)).isNull();
		assertThat(jdbc.queryForObject("SELECT password FROM member WHERE member_id = ?", String.class, id))
			.startsWith("$2").doesNotContain("brand-new-pass");
		assertThatThrownBy(() -> authService.login(email, PASSWORD)).isInstanceOf(BusinessException.class)
			.extracting(e -> ((BusinessException) e).getErrorCode().code()).isEqualTo("AUTH_INVALID_CREDENTIALS");
		assertThat(authService.login(email, "brand-new-pass").member().getMemberId()).isEqualTo(id);
	}

	@Test
	void 비밀번호_재설정은_OWNER_만_하고_본인과_잘못된_입력은_거절한다() throws Exception {
		long id = idOf(create(email, Role.STAFF));
		String body = "{\"newPassword\":\"brand-new-pass\"}";

		call(post("/api/v1/members/" + OWNER_ID + "/password").content(body), Role.OWNER)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("MEMBER_SELF_PASSWORD"));
		call(post("/api/v1/members/999999999/password").content(body), Role.OWNER).andExpect(status().isNotFound());
		call(post("/api/v1/members/" + id + "/password").content("{\"newPassword\":\"short\"}"), Role.OWNER)
			.andExpect(status().isBadRequest());
		call(post("/api/v1/members/" + id + "/password").content(body), Role.MANAGER)
			.andExpect(status().isForbidden());
		call(post("/api/v1/members/" + id + "/password").content(body), Role.STAFF)
			.andExpect(status().isForbidden());
		// 거절된 요청으로는 비밀번호가 바뀌지 않는다
		assertThat(authService.login(email, PASSWORD).member().getMemberId()).isEqualTo(id);
	}

	@Test
	void STAFF_도_현재_비밀번호를_확인하고_내_비밀번호를_바꾼다() throws Exception {
		long id = idOf(create(email, Role.STAFF));

		callAs(id, Role.STAFF, post("/api/v1/auth/password")
			.content("{\"currentPassword\":\"wrong-password\",\"newPassword\":\"brand-new-pass\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("AUTH_CURRENT_PASSWORD_INVALID"));
		assertThat(authService.login(email, PASSWORD).member().getMemberId()).isEqualTo(id);

		callAs(id, Role.STAFF, post("/api/v1/auth/password")
			.content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"brand-new-pass\"}"))
			.andExpect(status().isOk())
			.andExpect(header().exists("Set-Cookie"));
		assertThatThrownBy(() -> authService.login(email, PASSWORD)).isInstanceOf(BusinessException.class);
		assertThat(authService.login(email, "brand-new-pass").member().getMemberId()).isEqualTo(id);

		callAs(id, Role.STAFF, post("/api/v1/auth/password")
			.content("{\"currentPassword\":\"brand-new-pass\",\"newPassword\":\"short\"}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void 현재_비밀번호를_5회_틀리면_변경이_잠긴다() throws Exception {
		long id = idOf(create(email, Role.STAFF));
		String wrong = "{\"currentPassword\":\"wrong-password\",\"newPassword\":\"brand-new-pass\"}";

		for (int i = 0; i < 5; i++) {
			callAs(id, Role.STAFF, post("/api/v1/auth/password").content(wrong)).andExpect(status().isBadRequest());
		}
		// 잠긴 뒤에는 올바른 현재 비밀번호여도 거절한다 (일정 시간 뒤 풀림)
		callAs(id, Role.STAFF, post("/api/v1/auth/password")
			.content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"brand-new-pass\"}"))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.error.code").value("AUTH_PASSWORD_CHANGE_LOCKED"));
	}

	private String refreshHashOf(long id) {
		return jdbc.queryForObject("SELECT refresh_token_hash FROM member WHERE member_id = ?", String.class, id);
	}

	private ResultActions create(String email, Role role) throws Exception {
		return call(post("/api/v1/members").content(body(email, role, PASSWORD)), Role.OWNER);
	}

	private static String body(String email, Role role, String password) {
		return "{\"email\":\"%s\",\"name\":\"직원\",\"role\":\"%s\",\"password\":\"%s\"}".formatted(email, role, password);
	}

	private ResultActions call(MockHttpServletRequestBuilder request, Role role) throws Exception {
		return callAs(OWNER_ID, role, request);
	}

	private ResultActions callAs(long memberId, Role role, MockHttpServletRequestBuilder request) throws Exception {
		var auth = new UsernamePasswordAuthenticationToken(new AuthMember(memberId, role), null,
			List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
		return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).with(authentication(auth)).with(csrf()));
	}

	private static long idOf(ResultActions result) throws Exception {
		return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.data.memberId"))
			.longValue();
	}
}
