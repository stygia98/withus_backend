package com.withus.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.withus.ai.domain.AiErrorCode;
import com.withus.ai.dto.CampaignPlan;
import com.withus.common.domain.Channel;
import com.withus.common.exception.BusinessException;
import com.withus.coupon.domain.DiscountType;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** AI-04: LLM 응답 → 초안 변환과 검증 (실제 Gemini·DB 없이). 저장은 AiCampaignPlanApiTest */
class CampaignPlanServiceTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);
	private final ObjectMapper mapper = JsonMapper.builder().build();
	private final CampaignPlanService service = new CampaignPlanService(null, mapper, null, null, null, null);

	private CampaignPlan toPlan(String json) {
		return service.toPlan(mapper.readValue(json, CampaignPlanService.RawPlan.class), TODAY);
	}

	private static String plan(String channel, String conditions, String coupon) {
		return """
			{"campaignName":"부산 주말 특가","channel":"%s","segmentName":"부산 고객",
			 "conditions":%s,"coupon":%s,"templateName":"주말 특가 안내",
			 "subject":"(광고) {{name|고객}}님, 주말 특가","body":"{{name}}님 안녕하세요.\\n\\n<b>주말</b> 특가 {{email}} 안내.\\n\\n무료수신거부 080-123-4567"}
			""".formatted(channel, conditions, coupon);
	}

	@Test
	void 규칙에_메일_동의를_보충하고_쿠폰_날짜는_오늘부터_코드가_정한다() {
		CampaignPlan p = toPlan(plan("EMAIL", "[{\"field\":\"region\",\"op\":\"EQ\",\"value\":\"BUSAN\"}]",
			"{\"name\":\"10% 쿠폰\",\"discountType\":\"RATE\",\"discountValue\":10,\"maxDiscountAmount\":5000,\"validDays\":7}"));

		assertThat(p.channel()).isEqualTo(Channel.EMAIL);
		assertThat(p.segment().rule().toString())
			.isEqualTo("{\"operator\":\"AND\",\"groups\":[{\"operator\":\"AND\",\"conditions\":["
				+ "{\"field\":\"region\",\"op\":\"EQ\",\"value\":\"BUSAN\"},"
				+ "{\"field\":\"emailConsent\",\"op\":\"EQ\",\"value\":\"Y\"}]}]}");
		assertThat(p.coupon().discountType()).isEqualTo(DiscountType.RATE);
		assertThat(p.coupon().validFrom()).isEqualTo(TODAY);
		assertThat(p.coupon().validTo()).isEqualTo(TODAY.plusDays(6));
		// 제목의 (광고) 머리말, 다른 치환자·HTML, 수신거부 줄은 지운다 (발송 시 시스템이 넣는다)
		assertThat(p.template().subject()).isEqualTo("{{name|고객}}님, 주말 특가");
		assertThat(p.template().body()).isEqualTo("{{name|고객}}님 안녕하세요.\n\n주말 특가  안내.");
	}

	@Test
	void SMS는_SMS_동의를_보충하고_제목이_없다() {
		CampaignPlan p = toPlan(plan("SMS", "[]", "null"));

		assertThat(p.channel()).isEqualTo(Channel.SMS);
		assertThat(p.template().subject()).isNull();
		assertThat(p.coupon()).isNull();
		assertThat(p.segment().rule().toString()).contains("{\"field\":\"smsConsent\",\"op\":\"EQ\",\"value\":\"Y\"}");
	}

	@Test
	void 쿠폰_기간은_1일에서_90일로_제한한다() {
		CampaignPlan longer = toPlan(plan("EMAIL", "[]",
			"{\"name\":\"c\",\"discountType\":\"AMOUNT\",\"discountValue\":3000,\"maxDiscountAmount\":999,\"validDays\":400}"));
		assertThat(longer.coupon().validTo()).isEqualTo(TODAY.plusDays(89));
		// 정액은 최대 할인액을 쓰지 않는다
		assertThat(longer.coupon().maxDiscountAmount()).isNull();

		CampaignPlan shorter = toPlan(plan("EMAIL", "[]",
			"{\"name\":\"c\",\"discountType\":\"AMOUNT\",\"discountValue\":3000,\"validDays\":0}"));
		assertThat(shorter.coupon().validTo()).isEqualTo(TODAY);
	}

	@Test
	void 지역을_이름으로_보내면_코드로_바꾼다() {
		CampaignPlan p = toPlan(plan("EMAIL",
			"[{\"field\":\"region\",\"op\":\"IN\",\"value\":[\"서울\",\"경기도\",\"BUSAN\"]}]", "null"));
		assertThat(p.segment().rule().toString())
			.contains("{\"field\":\"region\",\"op\":\"IN\",\"value\":[\"SEOUL\",\"GYEONGGI\",\"BUSAN\"]}");
	}

	@Test
	void 오늘_가입을_0일로_보내면_1일로_바꾸고_다른_범위_밖_값은_AI_UNAVAILABLE() {
		CampaignPlan p = toPlan(plan("EMAIL", "[{\"field\":\"joinedAt\",\"op\":\"IN_LAST_DAYS\",\"value\":0}]", "null"));
		assertThat(p.segment().rule().toString())
			.contains("{\"field\":\"joinedAt\",\"op\":\"IN_LAST_DAYS\",\"value\":1}");

		assertUnavailable(plan("EMAIL", "[{\"field\":\"joinedAt\",\"op\":\"IN_LAST_DAYS\",\"value\":-1}]", "null"));
		assertUnavailable(plan("EMAIL", "[{\"field\":\"joinedAt\",\"op\":\"IN_LAST_DAYS\",\"value\":3651}]", "null"));
	}

	@Test
	void 형식만_다른_값은_규칙_형식으로_맞춘다() {
		CampaignPlan p = toPlan(plan("EMAIL", """
			[{"field":"age","op":"between","value":["20","29"]},
			 {"field":"totalPurchase","op":"GTE","value":"100,000"},
			 {"field":"region","op":"EQ","value":["서울","인천"]},
			 {"field":"dormant","op":"EQ","value":false},
			 {"field":"emailConsent","op":"EQ","value":"y"}]""", "null"));
		assertThat(p.segment().rule().toString()).isEqualTo("{\"operator\":\"AND\",\"groups\":[{\"operator\":\"AND\",\"conditions\":["
			+ "{\"field\":\"age\",\"op\":\"BETWEEN\",\"value\":[20,29]},"
			+ "{\"field\":\"totalPurchase\",\"op\":\"GTE\",\"value\":100000},"
			+ "{\"field\":\"region\",\"op\":\"IN\",\"value\":[\"SEOUL\",\"INCHEON\"]},"
			+ "{\"field\":\"dormant\",\"op\":\"EQ\",\"value\":\"N\"},"
			+ "{\"field\":\"emailConsent\",\"op\":\"EQ\",\"value\":\"Y\"}]}]}");

		assertThat(toPlan(plan("EMAIL", "[{\"field\":\"region\",\"op\":\"IN\",\"value\":\"부산\"}]", "null"))
			.segment().rule().toString()).contains("{\"field\":\"region\",\"op\":\"IN\",\"value\":[\"BUSAN\"]}");
		assertThat(toPlan(plan("EMAIL", "[{\"field\":\"region\",\"op\":\"EQ\",\"value\":[\"부산\"]}]", "null"))
			.segment().rule().toString()).contains("{\"field\":\"region\",\"op\":\"EQ\",\"value\":\"BUSAN\"}");
		// 뜻을 알 수 없는 값은 고치지 않는다
		assertUnavailable(plan("EMAIL", "[{\"field\":\"age\",\"op\":\"GTE\",\"value\":\"삼십\"}]", "null"));
		assertUnavailable(plan("EMAIL", "[{\"field\":\"dormant\",\"op\":\"EQ\",\"value\":\"maybe\"}]", "null"));
	}

	@Test
	void 반영하지_못한_조건은_한_줄로_정리하고_빈값_중복을_빼고_개수와_길이를_자른다() {
		assertThat(CampaignPlanService.unsupported(null)).isEmpty();
		assertThat(CampaignPlanService.unsupported(Arrays.asList(" 생일 ", "성별\n(여성)", "", null, "생일")))
			.containsExactly("생일", "성별 (여성)");
		assertThat(CampaignPlanService.unsupported(List.of("가".repeat(50), "1", "2", "3", "4", "5")))
			.hasSize(CampaignPlanService.UNSUPPORTED_MAX_COUNT)
			.first().asString().hasSize(CampaignPlanService.UNSUPPORTED_MAX_LENGTH);
	}

	@Test
	void 프롬프트에_오늘과_어제_올해_작년을_코드가_계산해_넣는다() {
		String system = CampaignPlanService.systemInstruction(LocalDate.of(2026, 1, 1));
		assertThat(system).contains("오늘은 2026-01-01 이다(어제 2025-12-31, 올해 2026년, 작년 2025년)")
			.contains("퍼센트(%)");
	}

	@Test
	void 화이트리스트_밖_필드나_연산자면_AI_UNAVAILABLE() {
		assertUnavailable(plan("EMAIL", "[{\"field\":\"email\",\"op\":\"EQ\",\"value\":\"a\"}]", "null"));
		assertUnavailable(plan("EMAIL", "[{\"field\":\"region\",\"op\":\"LIKE\",\"value\":\"BUSAN\"}]", "null"));
		assertUnavailable(plan("EMAIL", "[{\"field\":\"region\",\"op\":\"EQ\",\"value\":\"ATLANTIS\"}]", "null"));
	}

	@Test
	void 정률_쿠폰_상한이_없으면_기본값을_넣고_100을_넘으면_AI_UNAVAILABLE() {
		CampaignPlan p = toPlan(plan("EMAIL", "[]", "{\"name\":\"c\",\"discountType\":\"RATE\",\"discountValue\":10}"));
		assertThat(p.coupon().maxDiscountAmount()).isEqualTo(CampaignPlanService.DEFAULT_RATE_CAP);

		assertUnavailable(plan("EMAIL", "[]",
			"{\"name\":\"c\",\"discountType\":\"RATE\",\"discountValue\":150,\"maxDiscountAmount\":1000}"));
	}

	@Test
	void 메일_본문은_문단을_HTML로_바꾸고_쿠폰_링크를_붙인다() {
		assertThat(CampaignPlanService.body(Channel.EMAIL, "{{name|고객}}님 <안녕>\n둘째 줄\n\n끝", true))
			.isEqualTo("<p>{{name|고객}}님 &lt;안녕&gt;<br>둘째 줄</p><p>끝</p><p><a href=\"{{couponUrl}}\">쿠폰 받기</a></p>");
		assertThat(CampaignPlanService.body(Channel.SMS, "문자 본문", true)).isEqualTo("문자 본문\n\n쿠폰 받기: {{couponUrl}}");
		assertThat(CampaignPlanService.body(Channel.SMS, "문자 본문", false)).isEqualTo("문자 본문");
	}

	@Test
	void mock_응답도_같은_검증을_통과한다() {
		CampaignPlan p = service.toPlan(mapper.readValue(service.mockJson("부산 고객에게 10% 쿠폰"),
			CampaignPlanService.RawPlan.class), TODAY);
		assertThat(p.segment().rule().toString()).contains("BUSAN");
		assertThat(p.coupon().discountValue()).isEqualTo(10);
	}

	@Test
	void 예시는_번호와_따옴표를_떼고_중복을_빼고_보이던_예시를_함께_보낸다() {
		AtomicReference<LlmRequest> sent = new AtomicReference<>();
		CampaignPlanService s = new CampaignPlanService(request -> {
			sent.set(request);
			return new LlmResponse("{\"examples\":[\"1. 부산 고객에게 10% 쿠폰\",\"\\\"휴면 고객에게 안내 문자\\\"\","
				+ "\"부산 고객에게 10% 쿠폰\",\"최근 30일 가입 고객에게 환영 메일\",\"40대 메일 동의 고객에게 5,000원 쿠폰\"]}",
				"test-model", false);
		}, mapper, null, null, null, null);

		assertThat(s.examples(List.of("서울 고객에게 신상품 안내")))
			.containsExactly("부산 고객에게 10% 쿠폰", "휴면 고객에게 안내 문자", "최근 30일 가입 고객에게 환영 메일",
				"40대 메일 동의 고객에게 5,000원 쿠폰");
		assertThat(sent.get().prompt()).contains("서울 고객에게 신상품 안내");
		assertThat(sent.get().json()).isTrue();
	}

	@Test
	void 예시가_길면_띄어쓰기에서_자르고_끝_마침표를_뗀다() {
		assertThat(CampaignPlanService.example("부산 고객에게 쿠폰을 보냅니다.")).isEqualTo("부산 고객에게 쿠폰을 보냅니다");
		String longer = "SMS 수신에 동의한 부산 지역 거주 고객에게 이번 주말 매장 방문 시 사용할 수 있는 5,000원 할인 안내 문자";
		String cut = CampaignPlanService.example(longer);
		assertThat(cut).hasSizeLessThanOrEqualTo(60).doesNotEndWith(" ");
		assertThat(longer).startsWith(cut + " ");
	}

	@Test
	void 예시가_4개보다_적으면_AI_UNAVAILABLE() {
		CampaignPlanService s = new CampaignPlanService(
			request -> new LlmResponse("{\"examples\":[\"a\",\"b\",\"\",\"a\"]}", "test-model", false),
			mapper, null, null, null, null);
		assertThatThrownBy(() -> s.examples(null)).isInstanceOf(BusinessException.class)
			.extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(AiErrorCode.AI_UNAVAILABLE);
	}

	private void assertUnavailable(String json) {
		assertThatThrownBy(() -> toPlan(json)).isInstanceOf(BusinessException.class)
			.extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(AiErrorCode.AI_UNAVAILABLE);
	}
}
