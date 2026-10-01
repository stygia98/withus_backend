package com.withus.common.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** HTML 본문용 치환(renderHtml): 고객 값만 이스케이프하고 템플릿 HTML·기본값은 그대로 둔다 */
class PlaceholderRendererHtmlTest {

	private final PlaceholderRenderer renderer = new DefaultPlaceholderRenderer();

	@Test
	void 고객_값의_HTML_태그는_이스케이프된다() {
		String html = renderer.renderHtml("<p>{{name}}님</p>", Map.of("name", "<script>alert(1)</script>"));

		assertThat(html).isEqualTo("<p>&lt;script&gt;alert(1)&lt;/script&gt;님</p>");
		assertThat(html).doesNotContain("<script>");
	}

	@Test
	void 따옴표를_이용한_속성_삽입도_막는다() {
		String html = renderer.renderHtml("<a title=\"{{name}}\">링크</a>", Map.of("name", "\" onmouseover=\"x"));

		assertThat(html).isEqualTo("<a title=\"&quot; onmouseover=&quot;x\">링크</a>");
	}

	@Test
	void 앰퍼샌드와_작은따옴표도_바꾼다() {
		assertThat(renderer.renderHtml("{{name}}", Map.of("name", "A&B 'c'"))).isEqualTo("A&amp;B &#39;c&#39;");
	}

	@Test
	void 템플릿의_HTML과_기본값은_이스케이프하지_않는다() {
		// 값이 없어 템플릿 기본값(작성자가 쓴 텍스트)이 쓰이는 경우 — 이중 이스케이프가 일어나면 안 된다
		String html = renderer.renderHtml("<p><b>{{name|고객 &amp; 손님}}</b></p>", Map.of());

		assertThat(html).isEqualTo("<p><b>고객 &amp; 손님</b></p>");
	}

	@Test
	void 시스템_기본값도_그대로_들어간다() {
		assertThat(renderer.renderHtml("<p>{{name}} {{totalPurchase}}원</p>", Map.of())).isEqualTo("<p>고객 0원</p>");
	}

	@Test
	void 쿠폰_URL의_쿼리_앰퍼샌드는_속성_안에서_올바른_엔티티가_된다() {
		String html = renderer.renderHtml("<a href=\"{{couponUrl}}\">쿠폰</a>",
			Map.of("couponUrl", "https://x.test/c/abc?a=1&b=2"));

		assertThat(html).isEqualTo("<a href=\"https://x.test/c/abc?a=1&amp;b=2\">쿠폰</a>");
	}

	@Test
	void 한글과_일반_문자는_바뀌지_않는다() {
		assertThat(renderer.renderHtml("{{name}} {{region}}", Map.of("name", "홍길동", "region", "SEOUL")))
			.isEqualTo("홍길동 SEOUL");
	}

	@Test
	void 텍스트용_render는_이스케이프하지_않는다() {
		// 제목·SMS 에는 &amp; 가 그대로 보이면 안 되므로 render 는 원본 값을 쓴다
		assertThat(renderer.render("{{name}}님 소식", Map.of("name", "A&B"))).isEqualTo("A&B님 소식");
	}

	@Test
	void 값이_없거나_공백이면_이스케이프_전에_기본값_규칙이_적용된다() {
		Map<String, String> values = new HashMap<>();
		values.put("name", null);
		values.put("region", "   ");

		assertThat(renderer.renderHtml("{{name}} [{{region|서울}}]", values)).isEqualTo("고객 [서울]");
		assertThat(renderer.renderHtml("{{name}}", null)).isEqualTo("고객");
	}

	@Test
	void 입력_맵을_바꾸지_않는다() {
		Map<String, String> original = new HashMap<>(Map.of("name", "<b>"));

		renderer.renderHtml("{{name}}", original);

		assertThat(original).containsEntry("name", "<b>");
	}

	@Test
	void 치환된_값_안의_치환자는_HTML에서도_다시_치환되지_않는다() {
		assertThat(renderer.renderHtml("{{name}} {{email}}", Map.of("name", "{{email}}", "email", "a@b.com")))
			.isEqualTo("{{email}} a@b.com");
	}

	@Test
	void 기본값_사용_여부는_이스케이프와_무관하게_같다() {
		Map<String, String> values = Map.of("name", "<b>홍</b>");

		assertThat(renderer.usesDefault("{{name}}", values)).isFalse();
		assertThat(renderer.usesDefault("{{name}} {{region}}", values)).isTrue();
	}

	@Test
	void HtmlEscaper는_null과_빈_문자열을_그대로_돌려준다() {
		assertThat(HtmlEscaper.escape(null)).isNull();
		assertThat(HtmlEscaper.escape("")).isEmpty();
		assertThat(HtmlEscaper.escapeValues(null)).isNull();
	}
}
