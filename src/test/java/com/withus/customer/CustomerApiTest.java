package com.withus.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

/** 고객 개별 관리 API (PRD F-02, API_SPEC 3장). 로컬 Docker DB 를 쓰고 테스트마다 롤백한다 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CustomerApiTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;

	String email;

	@BeforeEach
	void setUp() {
		email = "cust-" + UUID.randomUUID() + "@withus.local";
	}

	@Test
	void 등록하면_정규화해서_저장하고_동의_이력을_남긴다() throws Exception {
		String body = """
			{"name":" 김민지 ","email":" %s ","phone":"010-1234-5678","region":"서울",
			 "birthDate":"1998-04-12","joinedAt":"2026-09-30","emailConsent":"Y","smsConsent":"N"}
			""".formatted(email.toUpperCase());

		long id = idOf(call(post("/api/v1/customers").content(body), Role.MANAGER)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.name").value("김민지"))
			.andExpect(jsonPath("$.data.email").value(email))
			.andExpect(jsonPath("$.data.phone").value("01012345678"))
			.andExpect(jsonPath("$.data.region").value("SEOUL"))
			.andExpect(jsonPath("$.data.emailConsent").value("Y"))
			.andExpect(jsonPath("$.data.emailConsentAt").exists())
			.andExpect(jsonPath("$.data.smsConsent").value("N"))
			.andExpect(jsonPath("$.data.source").value("MANUAL"))
			.andExpect(jsonPath("$.data.suppressedChannels").isEmpty()));

		List<String> history = jdbc.queryForList(
			"SELECT channel || ':' || coalesce(before_yn, '-') || '>' || after_yn || ':' || source"
				+ " FROM consent_history WHERE customer_id = ?", String.class, id);
		assertThat(history).containsExactly("EMAIL:->Y:ADMIN");
	}

	@Test
	void 삭제되지_않은_고객과_이메일이_같으면_409() throws Exception {
		create(email).andExpect(status().isOk());
		create(email.toUpperCase()).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("CUSTOMER_DUPLICATE_EMAIL"));
	}

	@Test
	void 삭제한_고객의_이메일로_다시_등록하면_새_고객이다() throws Exception {
		long oldId = idOf(create(email).andExpect(status().isOk()));
		call(delete("/api/v1/customers/" + oldId), Role.OWNER).andExpect(status().isOk());
		call(get("/api/v1/customers/" + oldId), Role.OWNER).andExpect(status().isNotFound());

		// 이전 고객은 동의 Y 였지만, 동의 없이 재등록하면 N (이어받지 않음)
		String noConsent = """
			{"email":"%s","joinedAt":"2026-09-30"}
			""".formatted(email);
		long newId = idOf(call(post("/api/v1/customers").content(noConsent), Role.MANAGER)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.emailConsent").value("N"))
			.andExpect(jsonPath("$.data.smsConsent").value("N")));
		assertThat(newId).isNotEqualTo(oldId);
	}

	@Test
	void suppression_에_있는_채널은_동의를_N으로_저장한다() throws Exception {
		jdbc.update("INSERT INTO suppression (channel, value, reason) VALUES ('EMAIL', ?, 'UNSUBSCRIBE')", email);

		create(email).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.emailConsent").value("N"))
			.andExpect(jsonPath("$.data.smsConsent").value("Y"))
			.andExpect(jsonPath("$.data.suppressedChannels[0]").value("EMAIL"))
			.andExpect(jsonPath("$.data.suppressedChannels.length()").value(1));
	}

	@Test
	void 수정은_누적구매액과_수신동의를_바꾸지_않는다() throws Exception {
		long id = idOf(create(email).andExpect(status().isOk()));
		jdbc.update("UPDATE customer SET total_purchase = 50000 WHERE customer_id = ?", id);

		String body = """
			{"name":"새이름","email":"%s","phone":"01099998888","region":"경기","joinedAt":"2026-01-02",
			 "totalPurchase":0,"emailConsent":"N"}
			""".formatted(email);
		call(patch("/api/v1/customers/" + id).content(body), Role.MANAGER)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.name").value("새이름"))
			.andExpect(jsonPath("$.data.region").value("GYEONGGI"))
			.andExpect(jsonPath("$.data.birthDate").doesNotExist())
			.andExpect(jsonPath("$.data.totalPurchase").value(50000))
			.andExpect(jsonPath("$.data.emailConsent").value("Y"));
	}

	@Test
	void 정규화_실패는_도메인_오류_코드로_400() throws Exception {
		String body = """
			{"email":"%s","phone":"02-123-4567","joinedAt":"2026-09-30"}
			""".formatted(email);
		call(post("/api/v1/customers").content(body), Role.MANAGER)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("CUSTOMER_INVALID_PHONE"));
	}

	@Test
	void STAFF_는_고객_API를_쓸_수_없다() throws Exception {
		call(post("/api/v1/customers").content(body(email)), Role.STAFF).andExpect(status().isForbidden());
		call(get("/api/v1/customers/1"), Role.STAFF).andExpect(status().isForbidden());
	}

	private ResultActions create(String email) throws Exception {
		return call(post("/api/v1/customers").content(body(email)), Role.MANAGER);
	}

	private static String body(String email) {
		return """
			{"email":"%s","joinedAt":"2026-09-30","emailConsent":"Y","smsConsent":"Y"}
			""".formatted(email);
	}

	private ResultActions call(MockHttpServletRequestBuilder request, Role role) throws Exception {
		var auth = new UsernamePasswordAuthenticationToken(new AuthMember(1L, role), null,
			List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
		return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).with(authentication(auth)).with(csrf()));
	}

	private static long idOf(ResultActions result) throws Exception {
		return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.data.customerId"))
			.longValue();
	}
}
