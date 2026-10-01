package com.withus.common.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 치환자 렌더러 (PRD F-04, 10.3 완료 기준: 이름이 없는 고객에게 "안녕하세요 고객님"으로 발송).
 * 우선순위: 고객 값 → 템플릿 기본값 → 시스템 기본값(이름 "고객", 지역 빈 값, 누적구매액 0)
 */
class PlaceholderRendererTest {

	private final PlaceholderRenderer renderer = new DefaultPlaceholderRenderer();

	private static Map<String, String> values(String... keyValues) {
		Map<String, String> map = new HashMap<>();
		for (int i = 0; i < keyValues.length; i += 2) {
			map.put(keyValues[i], keyValues[i + 1]);
		}
		return map;
	}

	// ---- 우선순위 ----

	@Test
	void 고객_값이_템플릿_기본값보다_우선한다() {
		assertThat(renderer.render("{{name|손님}}", values("name", "홍길동"))).isEqualTo("홍길동");
	}

	@Test
	void 고객_값이_없으면_템플릿_기본값을_쓴다() {
		assertThat(renderer.render("{{name|손님}}", values())).isEqualTo("손님");
	}

	@Test
	void 템플릿_기본값도_없으면_시스템_기본값을_쓴다() {
		assertThat(renderer.render("{{name}}", values())).isEqualTo("고객");
		assertThat(renderer.render("[{{region}}]", values())).isEqualTo("[]");
		assertThat(renderer.render("{{totalPurchase}}원", values())).isEqualTo("0원");
	}

	@Test
	void 이름이_없는_고객에게_안녕하세요_고객님으로_발송된다_PRD_10_3() {
		// 이름 값이 아예 없는 경우와 빈 값인 경우 모두 시스템 기본값 "고객"이 들어간다
		assertThat(renderer.render("안녕하세요 {{name}}님", values("email", "a@b.com"))).isEqualTo("안녕하세요 고객님");
		assertThat(renderer.render("안녕하세요 {{name}}님", values("name", ""))).isEqualTo("안녕하세요 고객님");
	}

	// ---- "값이 없다"의 정의 ----

	@Test
	void null과_공백_문자열은_값이_없는_것으로_본다() {
		Map<String, String> nullValue = new HashMap<>();
		nullValue.put("name", null);

		assertThat(renderer.render("{{name}}", nullValue)).isEqualTo("고객");
		assertThat(renderer.render("{{name}}", values("name", ""))).isEqualTo("고객");
		assertThat(renderer.render("{{name|손님}}", values("name", "   "))).isEqualTo("손님");
	}

	@Test
	void 빈_기본값은_지정하지_않은_것으로_보고_시스템_기본값을_쓴다() {
		assertThat(renderer.render("{{name|}}", values())).isEqualTo("고객");
		assertThat(renderer.render("{{name|  }}", values())).isEqualTo("고객");
	}

	@Test
	void values가_null이어도_동작한다() {
		assertThat(renderer.render("{{name}}님", null)).isEqualTo("고객님");
	}

	// ---- 문법 ----

	@Test
	void 중괄호_안의_공백과_기본값_앞뒤_공백을_허용한다() {
		assertThat(renderer.render("{{ name }}", values("name", "홍길동"))).isEqualTo("홍길동");
		assertThat(renderer.render("{{ name | 고객님 }}", values())).isEqualTo("고객님");
	}

	@Test
	void 같은_치환자가_여러_번_나와도_모두_치환한다() {
		assertThat(renderer.render("{{name}} / {{name}} / {{name|손님}}", values("name", "홍길동")))
			.isEqualTo("홍길동 / 홍길동 / 홍길동");
	}

	@Test
	void 기본값에_세로선이_있어도_그대로_쓴다() {
		assertThat(renderer.render("{{name|가|나}}", values())).isEqualTo("가|나");
	}

	@Test
	void 지원하지_않는_치환자와_깨진_문법은_그대로_둔다() {
		assertThat(renderer.render("{{foo}} {{Name}} {{name", values("name", "홍길동"))).isEqualTo("{{foo}} {{Name}} {{name");
		assertThat(renderer.render("{{ }} {{}}", values())).isEqualTo("{{ }} {{}}");
	}

	@Test
	void 치환자가_없으면_원문을_그대로_돌려준다() {
		String html = "<p>안녕하세요</p><a href=\"https://example.com/sale\">보기</a>";

		assertThat(renderer.render(html, values("name", "홍길동"))).isEqualTo(html);
	}

	@Test
	void 템플릿이_null이면_빈_문자열이다() {
		assertThat(renderer.render(null, values())).isEmpty();
		assertThat(renderer.usesDefault(null, values())).isFalse();
	}

	// ---- 안전성 ----

	@Test
	void 치환된_값_안의_치환자는_다시_치환하지_않는다() {
		String result = renderer.render("{{name}} {{email}}", values("name", "{{email}}", "email", "a@b.com"));

		assertThat(result).isEqualTo("{{email}} a@b.com");
	}

	@Test
	void 값에_달러와_역슬래시가_있어도_그대로_들어간다() {
		assertThat(renderer.render("{{name}}", values("name", "$1 \\ ${x} \\n"))).isEqualTo("$1 \\ ${x} \\n");
	}

	// ---- 실제 템플릿 형태 ----

	@Test
	void 제목과_HTML_본문과_쿠폰_링크를_치환한다() {
		Map<String, String> v = values("name", "홍길동", "couponUrl", "https://x.test/c/6f1c-uuid");

		assertThat(renderer.render("{{name|고객}}님께 드리는 가을 선물", v)).isEqualTo("홍길동님께 드리는 가을 선물");
		assertThat(renderer.render("<p>{{name|고객}}님</p><a href=\"{{couponUrl}}\">쿠폰 받기</a>", v))
			.isEqualTo("<p>홍길동님</p><a href=\"https://x.test/c/6f1c-uuid\">쿠폰 받기</a>");
	}

	@Test
	void 쿠폰_URL_값이_없으면_빈_값이다_발송_전_검증이_막는_경우() {
		assertThat(renderer.render("<a href=\"{{couponUrl}}\">쿠폰</a>", values())).isEqualTo("<a href=\"\">쿠폰</a>");
	}

	// ---- usesDefault (미리보기: "대상 n명 중 m명은 기본값으로 발송") ----

	@Test
	void 모든_값이_있으면_기본값을_쓰지_않는다() {
		assertThat(renderer.usesDefault("{{name|고객}} {{region}}", values("name", "홍길동", "region", "SEOUL"))).isFalse();
	}

	@Test
	void 하나라도_기본값으로_대체되면_true다() {
		assertThat(renderer.usesDefault("{{name|고객}} {{region}}", values("name", "홍길동"))).isTrue();
		assertThat(renderer.usesDefault("{{name}}", values("name", " "))).isTrue();
		assertThat(renderer.usesDefault("{{totalPurchase}}", values())).isTrue();
	}

	@Test
	void 치환자가_없거나_지원하지_않는_치환자만_있으면_false다() {
		assertThat(renderer.usesDefault("안녕하세요", values())).isFalse();
		assertThat(renderer.usesDefault("{{foo}}", values())).isFalse();
	}

	@Test
	void render와_usesDefault는_같은_기준으로_판단한다() {
		String template = "{{name|손님}}님, {{region|서울}} 소식";
		Map<String, String> v = values("name", "홍길동");

		assertThat(renderer.render(template, v)).isEqualTo("홍길동님, 서울 소식");
		assertThat(renderer.usesDefault(template, v)).isTrue();
	}
}
