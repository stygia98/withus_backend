package com.withus.segment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;
import com.withus.auth.domain.Role;
import com.withus.auth.security.AuthMember;
import com.withus.common.TestCsrf;
import com.withus.common.exception.BusinessException;
import com.withus.common.exception.CommonErrorCode;
import com.withus.segment.service.SegmentService;

/**
 * 세그먼트 규칙 → SQL 결과 (docs/plans/segment-sql.md 7장). 로컬 Docker DB, 테스트마다 롤백
 * 다른 데이터와 섞이지 않게 테스트 고객의 누적구매액을 고유 범위(base~base+99)로 두고 규칙에 그 범위를 건다
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SegmentApiTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	SegmentService segmentService;

	long base;
	long memberId;
	long seoul, gyeonggi, busan, deleted, joined29, joined30;
	LocalDate today;

	@BeforeEach
	void setUp() {
		base = 9_000_000_000L + (long) (Math.random() * 1_000_000_000L);
		memberId = jdbc.queryForObject("SELECT min(member_id) FROM member", Long.class);
		today = LocalDate.now(ZoneId.of("Asia/Seoul"));
		//                    지역        구매 오프셋 메일 SMS 휴면 가입일            삭제
		seoul = customer("SEOUL", 10, "Y", "N", "N", today.minusDays(1), "N");
		gyeonggi = customer("GYEONGGI", 20, "N", "Y", "Y", today.minusDays(100), "N");
		busan = customer("BUSAN", 30, "Y", "Y", "N", today.minusDays(10), "N");
		deleted = customer("SEOUL", 40, "Y", "Y", "N", today, "Y");
		joined29 = customer("JEJU", 50, "N", "N", "N", today.minusDays(29), "N");
		joined30 = customer("JEJU", 60, "N", "N", "N", today.minusDays(30), "N");
	}

	@Test
	void 만_나이_20_34세는_생일_기준으로_자른다() throws Exception {
		birth(seoul, today.minusYears(20)); //                    오늘 20번째 생일 → 20세 포함
		birth(gyeonggi, today.minusYears(20).plusDays(1)); //     내일 20번째 생일 → 19세 제외
		birth(busan, today.minusYears(35).plusDays(1)); //        내일 35번째 생일 → 34세 포함
		birth(joined29, today.minusYears(35)); //                 오늘 35번째 생일 → 35세 제외
		// joined30 은 생년월일 없음 → 제외
		String rule = isolated("{\"field\":\"age\",\"op\":\"BETWEEN\",\"value\":[20,34]}");
		assertThat(segmentService.findTargetCustomers(create(rule))).containsExactly(seoul, busan);
	}

	@Test
	void 가입일_범위는_양_끝_포함() throws Exception {
		String rule = isolated("{\"field\":\"joinedAt\",\"op\":\"BETWEEN\",\"value\":[\"%s\",\"%s\"]}"
			.formatted(today.minusDays(29), today.minusDays(10)));
		assertThat(segmentService.findTargetCustomers(create(rule))).containsExactly(busan, joined29);
	}

	@Test
	void 지역_NE_는_지역이_빈_고객을_포함하지_않는다() throws Exception {
		long noRegion = customer(null, 70, "Y", "Y", "N", today, "N");
		String rule = isolated("{\"field\":\"region\",\"op\":\"NE\",\"value\":\"SEOUL\"}");
		assertThat(segmentService.findTargetCustomers(create(rule)))
			.containsExactly(gyeonggi, busan, joined29, joined30)
			.doesNotContain(noRegion);
	}

	@Test
	void 수정하면_바뀐_규칙으로_바로_다시_센다() throws Exception {
		long id = create(isolated("{\"field\":\"dormant\",\"op\":\"EQ\",\"value\":\"N\"}"));
		String body = body("휴면 고객", isolated("{\"field\":\"dormant\",\"op\":\"EQ\",\"value\":\"Y\"}"));
		call(put("/api/v1/segments/" + id).content(body), Role.MANAGER).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.name").value("휴면 고객"))
			.andExpect(jsonPath("$.data.targetCount").value(1));
		assertThat(segmentService.findTargetCustomers(id)).containsExactly(gyeonggi);

		call(put("/api/v1/segments/" + id).content(body("잘못", "{\"operator\":\"AND\",\"groups\":[]}")),
			Role.MANAGER).andExpect(jsonPath("$.error.code").value("SEGMENT_INVALID_RULE"));
		call(put("/api/v1/segments/" + id).content(body), Role.STAFF).andExpect(status().isForbidden());
		call(put("/api/v1/segments/" + Long.MAX_VALUE).content(body), Role.MANAGER)
			.andExpect(status().isNotFound());
	}

	@Test
	void 캠페인이_쓰는_세그먼트는_삭제할_수_없다() throws Exception {
		String rule = isolated("{\"field\":\"dormant\",\"op\":\"EQ\",\"value\":\"N\"}");
		long used = create(rule);
		jdbc.update("INSERT INTO campaign (name, type, segment_id, created_by) VALUES ('테스트', 'ONE_TIME', ?, ?)",
			used, memberId);
		call(delete("/api/v1/segments/" + used), Role.MANAGER).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("SEGMENT_IN_USE"));

		long unused = create(rule);
		call(delete("/api/v1/segments/" + unused), Role.STAFF).andExpect(status().isForbidden());
		call(delete("/api/v1/segments/" + unused), Role.MANAGER).andExpect(status().isOk());
		call(get("/api/v1/segments/" + unused), Role.MANAGER).andExpect(status().isNotFound());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM segment_rule WHERE segment_id = ?", Long.class,
			unused)).as("규칙도 CASCADE 로 삭제").isZero();
	}

	@Test
	void 필드_목록은_서버_화이트리스트_그대로() throws Exception {
		call(get("/api/v1/segments/fields"), Role.STAFF).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.length()").value(7))
			.andExpect(jsonPath("$.data[0].field").value("region"))
			.andExpect(jsonPath("$.data[0].operators").value(org.hamcrest.Matchers.contains("EQ", "NE", "IN")))
			.andExpect(jsonPath("$.data[?(@.field == 'age')].operators[*]").value(
				org.hamcrest.Matchers.contains("EQ", "GT", "GTE", "LT", "LTE", "BETWEEN")))
			.andExpect(jsonPath("$.data[?(@.field == 'joinedAt')].operators[*]").value(
				org.hamcrest.Matchers.contains("BETWEEN", "IN_LAST_DAYS")));
	}

	private void birth(long customerId, LocalDate birthDate) {
		jdbc.update("UPDATE customer SET birth_date = ? WHERE customer_id = ?", birthDate, customerId);
	}

	@Test
	void 지역_IN_은_삭제_고객을_빼고_미리보기_4개_숫자를_한번에_센다() throws Exception {
		String rule = isolated("{\"field\":\"region\",\"op\":\"IN\",\"value\":[\"SEOUL\",\"GYEONGGI\"]}");
		preview(rule, Role.MANAGER).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.total").value(2))
			.andExpect(jsonPath("$.data.emailConsent").value(1))
			.andExpect(jsonPath("$.data.smsConsent").value(1))
			.andExpect(jsonPath("$.data.dormant").value(1));

		long id = create(rule);
		assertThat(segmentService.findTargetCustomers(id)).containsExactly(seoul, gyeonggi);
	}

	@Test
	void 그룹_간_AND_그룹_안_OR() throws Exception {
		String rule = """
			{"operator":"AND","groups":[
			  {"operator":"AND","conditions":[{"field":"totalPurchase","op":"BETWEEN","value":[%d,%d]}]},
			  {"operator":"OR","conditions":[{"field":"region","op":"EQ","value":"BUSAN"},
			                                 {"field":"dormant","op":"EQ","value":"Y"}]}]}
			""".formatted(base, base + 99);
		assertThat(segmentService.findTargetCustomers(create(rule))).containsExactly(gyeonggi, busan);
	}

	@Test
	void 그룹_간_OR_는_범위를_넓힌다() throws Exception {
		// (범위 AND 메일 Y) OR (범위 AND SMS Y) → 메일 또는 SMS 동의
		String rule = """
			{"operator":"OR","groups":[
			  {"operator":"AND","conditions":[{"field":"totalPurchase","op":"BETWEEN","value":[%1$d,%2$d]},
			                                  {"field":"emailConsent","op":"EQ","value":"Y"}]},
			  {"operator":"AND","conditions":[{"field":"totalPurchase","op":"BETWEEN","value":[%1$d,%2$d]},
			                                  {"field":"smsConsent","op":"EQ","value":"Y"}]}]}
			""".formatted(base, base + 99);
		assertThat(segmentService.findTargetCustomers(create(rule))).containsExactly(seoul, gyeonggi, busan);
	}

	@Test
	void 최근_30일은_오늘_포함_30일이다() throws Exception {
		String rule = isolated("{\"field\":\"joinedAt\",\"op\":\"IN_LAST_DAYS\",\"value\":30}");
		assertThat(segmentService.findTargetCustomers(create(rule)))
			.containsExactly(seoul, busan, joined29)
			.doesNotContain(joined30, gyeonggi);
	}

	@Test
	void 구매액_비교_연산자() throws Exception {
		String rule = isolated("{\"field\":\"totalPurchase\",\"op\":\"GTE\",\"value\":" + (base + 30) + "}");
		assertThat(segmentService.findTargetCustomers(create(rule))).containsExactly(busan, joined29, joined30);
		String lt = isolated("{\"field\":\"totalPurchase\",\"op\":\"LT\",\"value\":" + (base + 20) + "}");
		assertThat(segmentService.findTargetCustomers(create(lt))).containsExactly(seoul);
	}

	@Test
	void PRD_10_3_서울_경기_구매액_10만원_이상_미리보기() throws Exception {
		String rule = """
			{"operator":"AND","groups":[{"operator":"AND","conditions":[
			  {"field":"region","op":"IN","value":["SEOUL","GYEONGGI"]},
			  {"field":"totalPurchase","op":"GTE","value":100000}]}]}
			""";
		long expected = jdbc.queryForObject("SELECT count(*) FROM customer WHERE deleted_yn = 'N'"
			+ " AND region_code IN ('SEOUL','GYEONGGI') AND total_purchase >= 100000", Long.class);
		preview(rule, Role.MANAGER).andExpect(jsonPath("$.data.total").value(expected));

		long id = create(rule);
		call(get("/api/v1/segments/" + id), Role.STAFF).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.targetCount").value(expected))
			.andExpect(jsonPath("$.data.rule.groups[0].conditions[0].value[1]").value("GYEONGGI"));
		assertThat(segmentService.findTargetCustomers(id)).contains(seoul, gyeonggi).doesNotContain(busan, deleted);
	}

	@Test
	void 생성_목록과_권한() throws Exception {
		String rule = isolated("{\"field\":\"dormant\",\"op\":\"EQ\",\"value\":\"N\"}");
		long id = create(rule);
		call(get("/api/v1/segments?size=100"), Role.STAFF).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content[?(@.segmentId == " + id + ")].targetCount").value(4));

		call(post("/api/v1/segments").content(body("직원", rule)), Role.STAFF).andExpect(status().isForbidden());
		preview(rule, Role.STAFF).andExpect(status().isForbidden());
		call(get("/api/v1/segments?size=101"), Role.MANAGER).andExpect(status().isBadRequest());
		call(get("/api/v1/segments/" + Long.MAX_VALUE), Role.MANAGER).andExpect(status().isNotFound());
	}

	@Test
	void 잘못된_규칙은_저장하지_않고_위치를_알려준다() throws Exception {
		String bad = isolated("{\"field\":\"region\",\"op\":\"GT\",\"value\":\"SEOUL\"}");
		call(post("/api/v1/segments").content(body("잘못", bad)), Role.MANAGER)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("SEGMENT_INVALID_RULE"))
			.andExpect(jsonPath("$.error.details.path").value("groups[0].conditions[1]"));
		preview("{\"operator\":\"AND\",\"groups\":[]}", Role.MANAGER)
			.andExpect(jsonPath("$.error.code").value("SEGMENT_INVALID_RULE"));
	}

	@Test
	void 없는_세그먼트는_NOT_FOUND() {
		assertThatThrownBy(() -> segmentService.findTargetCustomers(Long.MAX_VALUE))
			.isInstanceOf(BusinessException.class)
			.extracting(e -> ((BusinessException) e).getErrorCode())
			.isEqualTo(CommonErrorCode.COMMON_NOT_FOUND);
	}

	/** 테스트 고객만 남기는 범위 조건 + 검사할 조건 하나 */
	private String isolated(String condition) {
		return """
			{"operator":"AND","groups":[{"operator":"AND","conditions":[
			  {"field":"totalPurchase","op":"BETWEEN","value":[%d,%d]}, %s]}]}
			""".formatted(base, base + 99, condition);
	}

	private long customer(String region, int purchaseOffset, String email, String sms, String dormant,
		LocalDate joinedAt, String deletedYn) {
		return jdbc.queryForObject("""
			INSERT INTO customer (email, region_code, joined_at, total_purchase, email_consent_yn, sms_consent_yn,
			                      dormant_yn, source, deleted_yn)
			VALUES (?, ?, ?, ?, ?, ?, ?, 'MANUAL', ?) RETURNING customer_id
			""", Long.class, "seg-" + base + "-" + purchaseOffset + "@withus.local", region, joinedAt,
			base + purchaseOffset, email, sms, dormant, deletedYn);
	}

	private long create(String rule) throws Exception {
		String res = call(post("/api/v1/segments").content(body("테스트 세그먼트", rule)), Role.MANAGER)
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(res, "$.data.segmentId")).longValue();
	}

	private static String body(String name, String rule) {
		return "{\"name\":\"" + name + "\",\"rule\":" + rule + "}";
	}

	private ResultActions preview(String rule, Role role) throws Exception {
		return call(post("/api/v1/segments/preview").content("{\"rule\":" + rule + "}"), role);
	}

	private ResultActions call(MockHttpServletRequestBuilder request, Role role) throws Exception {
		return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).with(auth(role)).with(TestCsrf.issue(mvc)));
	}

	private RequestPostProcessor auth(Role role) {
		return authentication(new UsernamePasswordAuthenticationToken(new AuthMember(memberId, role), null,
			List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
	}
}
