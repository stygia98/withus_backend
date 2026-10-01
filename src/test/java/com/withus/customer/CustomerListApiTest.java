package com.withus.customer;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;
import com.withus.auth.domain.Role;
import com.withus.auth.security.AuthMember;

/** 고객 목록 API (API_SPEC 3장 GET /customers). 테스트마다 고유 태그로 자기 데이터만 검색한다 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CustomerListApiTest {

	@Autowired
	MockMvc mvc;

	String tag;
	String phoneTail;

	@BeforeEach
	void setUp() throws Exception {
		// 숫자가 섞이면 검색어가 휴대폰 검색으로도 쓰여 다른 고객 번호와 우연히 겹칠 수 있어 문자만 쓴다 (0~9 → g~p)
		tag = UUID.randomUUID().toString().substring(0, 8).chars().map(c -> Character.isDigit(c) ? c - '0' + 'g' : c)
			.collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
		phoneTail = String.valueOf(1000 + (int) (Math.random() * 9000));
		create("가" + tag, "a" + tag + "@withus.local", "010-1111-" + phoneTail, "서울", "2026-01-03", "Y");
		create("나" + tag, "b" + tag + "@withus.local", null, "경기", "2026-01-01", "N");
		long deleted = create("다" + tag, "c" + tag + "@withus.local", null, "서울", "2026-01-02", "Y");
		call(delete("/api/v1/customers/" + deleted)).andExpect(status().isOk());
	}

	@Test
	void 검색어로_찾고_삭제_고객은_빼고_이메일_휴대폰을_마스킹한다() throws Exception {
		list("keyword=" + tag.toUpperCase() + "&sort=name,asc")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalElements").value(2))
			.andExpect(jsonPath("$.data.content[0].name").value("가" + tag))
			.andExpect(jsonPath("$.data.content[0].email").value("a" + tag.charAt(0) + "***@withus.local"))
			.andExpect(jsonPath("$.data.content[0].phone").value("010****" + phoneTail))
			.andExpect(jsonPath("$.data.content[1].phone").doesNotExist());
	}

	@Test
	void 휴대폰은_숫자만으로_비교한다() throws Exception {
		list("keyword=010-1111-" + phoneTail)
			.andExpect(jsonPath("$.data.content[?(@.name == '가" + tag + "')]").isNotEmpty());
	}

	@Test
	void 지역_동의_필터와_정렬_페이징() throws Exception {
		list("keyword=" + tag + "&region=서울").andExpect(jsonPath("$.data.totalElements").value(1))
			.andExpect(jsonPath("$.data.content[0].region").value("SEOUL"));
		list("keyword=" + tag + "&emailConsent=N").andExpect(jsonPath("$.data.totalElements").value(1))
			.andExpect(jsonPath("$.data.content[0].name").value("나" + tag));
		list("keyword=" + tag + "&sort=joinedAt,asc&size=1&page=0")
			.andExpect(jsonPath("$.data.content.length()").value(1))
			.andExpect(jsonPath("$.data.content[0].name").value("나" + tag))
			.andExpect(jsonPath("$.data.totalPages").value(2));
	}

	@Test
	void 검색어의_와일드카드는_문자_그대로_찾는다() throws Exception {
		// 쿼리 문자열에 %25 를 직접 쓰면 MockMvc 가 다시 인코딩해 % 가 서버에 닿지 않는다 → param 으로 넘긴다
		call(get("/api/v1/customers").param("keyword", "%" + tag)).andExpect(jsonPath("$.data.totalElements").value(0));
		call(get("/api/v1/customers").param("keyword", "_" + tag.substring(1)))
			.andExpect(jsonPath("$.data.totalElements").value(0));
	}

	@Test
	void 허용되지_않은_정렬_필터_크기는_400() throws Exception {
		for (String q : List.of("sort=email", "sort=name,up", "sort=name;DROP", "size=101", "page=-1",
			"emailConsent=X")) {
			list(q).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
		}
		list("region=화성").andExpect(jsonPath("$.error.code").value("CUSTOMER_INVALID_REGION"));
	}

	@Test
	void STAFF_는_목록을_볼_수_없다() throws Exception {
		mvc.perform(get("/api/v1/customers").with(auth(Role.STAFF))).andExpect(status().isForbidden());
	}

	private ResultActions list(String query) throws Exception {
		return call(get("/api/v1/customers?" + query));
	}

	private long create(String name, String email, String phone, String region, String joinedAt, String consent)
		throws Exception {
		String body = """
			{"name":"%s","email":"%s","phone":%s,"region":"%s","joinedAt":"%s","emailConsent":"%s"}
			""".formatted(name, email, phone == null ? "null" : "\"" + phone + "\"", region, joinedAt, consent);
		String res = call(post("/api/v1/customers").content(body)).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(res, "$.data.customerId")).longValue();
	}

	private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).with(auth(Role.MANAGER)).with(csrf()));
	}

	private static org.springframework.test.web.servlet.request.RequestPostProcessor auth(Role role) {
		return authentication(new UsernamePasswordAuthenticationToken(new AuthMember(1L, role), null,
			List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
	}
}
