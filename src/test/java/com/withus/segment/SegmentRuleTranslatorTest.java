package com.withus.segment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.withus.common.exception.BusinessException;
import com.withus.segment.domain.LogicalOperator;
import com.withus.segment.domain.SegmentErrorCode;
import com.withus.segment.domain.SegmentField;
import com.withus.segment.domain.SegmentOperator;
import com.withus.segment.domain.SegmentQuery;
import com.withus.segment.domain.SegmentQuery.Condition;
import com.withus.segment.service.SegmentRuleTranslator;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 세그먼트 규칙 JSON → SQL 변환 (CLAUDE.md 테스트 필수 대상). DB 없이 실행된다 */
class SegmentRuleTranslatorTest {

	static final JsonMapper JSON = JsonMapper.builder().build();
	static final LocalDate TODAY = LocalDate.of(2026, 10, 1);

	@Test
	void PRD_시나리오_서울_경기_구매액_10만원_이상() {
		SegmentQuery q = translate("""
			{"operator":"AND","groups":[{"operator":"AND","conditions":[
			  {"field":"region","op":"IN","value":["SEOUL","GYEONGGI","SEOUL"]},
			  {"field":"totalPurchase","op":"GTE","value":100000}]}]}
			""");
		assertThat(q.operator()).isEqualTo(LogicalOperator.AND);
		List<Condition> cs = q.groups().get(0).conditions();
		assertThat(cs.get(0).field()).isEqualTo(SegmentField.REGION);
		assertThat(cs.get(0).values()).containsExactly("SEOUL", "GYEONGGI");
		assertThat(cs.get(1)).isEqualTo(Condition.single(SegmentField.TOTAL_PURCHASE, SegmentOperator.GTE, 100000L));
	}

	@Test
	void 그룹_간_OR_와_BETWEEN() {
		SegmentQuery q = translate("""
			{"operator":"OR","groups":[
			  {"operator":"OR","conditions":[{"field":"emailConsent","op":"EQ","value":"Y"},
			                                 {"field":"dormant","op":"EQ","value":"N"}]},
			  {"operator":"AND","conditions":[{"field":"totalPurchase","op":"BETWEEN","value":[1000,5000]}]}]}
			""");
		assertThat(q.operator()).isEqualTo(LogicalOperator.OR);
		assertThat(q.groups().get(0).operator()).isEqualTo(LogicalOperator.OR);
		assertThat(q.groups().get(1).conditions().get(0))
			.isEqualTo(new Condition(SegmentField.TOTAL_PURCHASE, SegmentOperator.BETWEEN, 1000L, 5000L, null));
	}

	@Test
	void 최근_N일은_오늘_포함_N일_joined_at_초과_today_빼기_N() {
		Condition c = translate(rule("{\"field\":\"joinedAt\",\"op\":\"IN_LAST_DAYS\",\"value\":90}"))
			.groups().get(0).conditions().get(0);
		// 2026-10-01 기준 90일 → joined_at > 2026-07-03 (07-04 ~ 10-01, 오늘 포함 90일)
		assertThat(c).isEqualTo(Condition.single(SegmentField.JOINED_AT, SegmentOperator.GT, LocalDate.of(2026, 7, 3)));
		Condition one = translate(rule("{\"field\":\"joinedAt\",\"op\":\"IN_LAST_DAYS\",\"value\":1}"))
			.groups().get(0).conditions().get(0);
		assertThat(one.value()).as("N=1 이면 오늘 가입자만").isEqualTo(TODAY.minusDays(1));
	}

	@Test
	void 만_나이_BETWEEN_은_DB_SCHEMA_식과_같은_birth_date_범위() {
		// age 20~34 @2026-10-01 → birth_date > 1991-10-01 AND <= 2006-10-01 (DB_SCHEMA 5.1)
		assertThat(ageCondition("BETWEEN", "[20,34]")).isEqualTo(new Condition(SegmentField.AGE,
			SegmentOperator.BETWEEN, LocalDate.of(1991, 10, 2), LocalDate.of(2006, 10, 1), null));
	}

	@Test
	void 만_나이_단일_연산자_변환() {
		assertThat(ageCondition("GTE", "20"))
			.isEqualTo(Condition.single(SegmentField.AGE, SegmentOperator.LTE, LocalDate.of(2006, 10, 1)));
		assertThat(ageCondition("GT", "20")).as("21세 이상")
			.isEqualTo(Condition.single(SegmentField.AGE, SegmentOperator.LTE, LocalDate.of(2005, 10, 1)));
		assertThat(ageCondition("LTE", "34")).as("35번째 생일 전까지")
			.isEqualTo(Condition.single(SegmentField.AGE, SegmentOperator.GTE, LocalDate.of(1991, 10, 2)));
		assertThat(ageCondition("LT", "35")).as("34세 이하와 같다")
			.isEqualTo(ageCondition("LTE", "34"));
		assertThat(ageCondition("EQ", "30")).isEqualTo(new Condition(SegmentField.AGE, SegmentOperator.BETWEEN,
			LocalDate.of(1995, 10, 2), LocalDate.of(1996, 10, 1), null));
	}

	@Test
	void 만_나이_생일_경계() {
		// 2006-10-01 생은 오늘(2026-10-01) 생일이라 만 20세, 2006-10-02 생은 아직 19세
		Condition c = ageCondition("GTE", "20");
		LocalDate youngest = (LocalDate) c.value();
		assertThat(LocalDate.of(2006, 10, 1)).isBeforeOrEqualTo(youngest);
		assertThat(LocalDate.of(2006, 10, 2)).isAfter(youngest);
	}

	@Test
	void 기준일이_2월_29일이면_평년은_2월_28일로_맞춘다() {
		Condition c = SegmentRuleTranslator.translate(JSON.readTree(rule(
			"{\"field\":\"age\",\"op\":\"GTE\",\"value\":1}")), LocalDate.of(2028, 2, 29)).groups().get(0)
			.conditions().get(0);
		assertThat(c.value()).isEqualTo(LocalDate.of(2027, 2, 28));
	}

	@Test
	void 가입일_BETWEEN_과_지역_NE() {
		Condition joined = translate(rule(
			"{\"field\":\"joinedAt\",\"op\":\"BETWEEN\",\"value\":[\"2026-01-01\",\"2026-03-31\"]}"))
			.groups().get(0).conditions().get(0);
		assertThat(joined).isEqualTo(new Condition(SegmentField.JOINED_AT, SegmentOperator.BETWEEN,
			LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31), null));
		Condition ne = translate(rule("{\"field\":\"region\",\"op\":\"NE\",\"value\":\"SEOUL\"}"))
			.groups().get(0).conditions().get(0);
		assertThat(ne).isEqualTo(Condition.single(SegmentField.REGION, SegmentOperator.NE, "SEOUL"));
	}

	private static Condition ageCondition(String op, String value) {
		return translate(rule("{\"field\":\"age\",\"op\":\"" + op + "\",\"value\":" + value + "}"))
			.groups().get(0).conditions().get(0);
	}

	@Test
	void 조건은_그룹_합계_10개까지() {
		// 두 그룹 6 + 4 = 10개는 허용, 6 + 5 = 11개는 TOO_MANY
		assertThat(translate(twoGroups(6, 4)).groups()).hasSize(2);
		assertThatThrownBy(() -> translate(twoGroups(6, 5))).isInstanceOf(BusinessException.class)
			.extracting(e -> ((BusinessException) e).getErrorCode())
			.isEqualTo(SegmentErrorCode.SEGMENT_TOO_MANY_CONDITIONS);
	}

	private static String twoGroups(int first, int second) {
		String c = "{\"field\":\"dormant\",\"op\":\"EQ\",\"value\":\"N\"}";
		return "{\"operator\":\"AND\",\"groups\":[{\"operator\":\"AND\",\"conditions\":["
			+ String.join(",", java.util.Collections.nCopies(first, c)) + "]},{\"operator\":\"OR\",\"conditions\":["
			+ String.join(",", java.util.Collections.nCopies(second, c)) + "]}]}";
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
		{"operator":"AND","groups":[]}                                                        | groups
		{"operator":"AND"}                                                                    | groups
		{"operator":"XOR","groups":[{"operator":"AND","conditions":[{"field":"dormant","op":"EQ","value":"N"}]}]} | operator
		{"operator":"AND","groups":[{"operator":"and","conditions":[{"field":"dormant","op":"EQ","value":"N"}]}]} | groups[0].operator
		{"operator":"AND","groups":[{"operator":"AND","conditions":[]}]}                      | groups[0]
		{"operator":"AND","groups":[{"operator":"AND","groups":[],"conditions":[{"field":"dormant","op":"EQ","value":"N"}]}]} | groups[0]
		""")
	void 구조_오류는_INVALID_RULE_과_경로(String json, String path) {
		assertInvalid(json, path);
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"{\"field\":\"age\",\"op\":\"IN\",\"value\":[20]}",
		"{\"field\":\"age\",\"op\":\"GTE\",\"value\":151}",
		"{\"field\":\"age\",\"op\":\"BETWEEN\",\"value\":[34,20]}",
		"{\"field\":\"joinedAt\",\"op\":\"BETWEEN\",\"value\":[\"2026/01/01\",\"2026-02-01\"]}",
		"{\"field\":\"joinedAt\",\"op\":\"BETWEEN\",\"value\":[\"2026-02-30\",\"2026-03-01\"]}",
		"{\"field\":\"joinedAt\",\"op\":\"BETWEEN\",\"value\":[\"2026-03-01\",\"2026-02-01\"]}",
		"{\"field\":\"joinedAt\",\"op\":\"BETWEEN\",\"value\":[\"2026-03-01\"]}",
		"{\"field\":\"region\",\"op\":\"NE\",\"value\":[\"SEOUL\"]}",
		"{\"field\":\"region\",\"op\":\"GT\",\"value\":\"SEOUL\"}",
		"{\"field\":\"emailConsent\",\"op\":\"NE\",\"value\":\"Y\"}",
		"{\"field\":\"totalPurchase\",\"op\":\"IN\",\"value\":[1]}",
		"{\"field\":\"joinedAt\",\"op\":\"EQ\",\"value\":3}",
		"{\"field\":\"dormant\",\"op\":\"eq\",\"value\":\"N\"}",
		"{\"field\":\"region\",\"op\":\"IN\",\"value\":[]}",
		"{\"field\":\"region\",\"op\":\"IN\",\"value\":[\"서울\"]}",
		"{\"field\":\"region\",\"op\":\"EQ\",\"value\":\"seoul\"}",
		"{\"field\":\"totalPurchase\",\"op\":\"GTE\",\"value\":-1}",
		"{\"field\":\"totalPurchase\",\"op\":\"GTE\",\"value\":\"100000\"}",
		"{\"field\":\"totalPurchase\",\"op\":\"GTE\",\"value\":1.5}",
		"{\"field\":\"totalPurchase\",\"op\":\"BETWEEN\",\"value\":[5000]}",
		"{\"field\":\"totalPurchase\",\"op\":\"BETWEEN\",\"value\":[5000,1000]}",
		"{\"field\":\"joinedAt\",\"op\":\"IN_LAST_DAYS\",\"value\":0}",
		"{\"field\":\"joinedAt\",\"op\":\"IN_LAST_DAYS\",\"value\":3651}",
		"{\"field\":\"smsConsent\",\"op\":\"EQ\",\"value\":\"y\"}",
		"{\"field\":\"smsConsent\",\"op\":\"EQ\"}" })
	void 필드_연산자_값_오류는_INVALID_RULE(String condition) {
		assertThatThrownBy(() -> translate(rule(condition))).isInstanceOf(BusinessException.class)
			.satisfies(e -> {
				BusinessException be = (BusinessException) e;
				assertThat(be.getErrorCode()).isEqualTo(SegmentErrorCode.SEGMENT_INVALID_RULE);
				assertThat(((Map<?, ?>) be.getDetails()).get("path").toString())
					.startsWith("groups[0].conditions[0]");
			});
	}

	private static void assertInvalid(String json, String path) {
		assertThatThrownBy(() -> translate(json)).isInstanceOf(BusinessException.class).satisfies(e -> {
			BusinessException be = (BusinessException) e;
			assertThat(be.getErrorCode()).isEqualTo(SegmentErrorCode.SEGMENT_INVALID_RULE);
			assertThat(((Map<?, ?>) be.getDetails()).get("path")).isEqualTo(path);
		});
	}

	private static String rule(String condition) {
		return "{\"operator\":\"AND\",\"groups\":[{\"operator\":\"AND\",\"conditions\":[" + condition + "]}]}";
	}

	private static SegmentQuery translate(String json) {
		JsonNode node = JSON.readTree(json);
		return SegmentRuleTranslator.translate(node, TODAY);
	}
}
