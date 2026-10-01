package com.withus.tracking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.withus.tracking.config.TrackingProperties;
import com.withus.tracking.service.TrackingHtmlRewriter;

/** 링크 치환 규칙 (PRD 8.1): 일반 링크만 추적 URL 로 바꾸고, 수신거부·쿠폰·mailto·tel 은 그대로 둔다 */
class TrackingHtmlRewriterTest {

	private static final String BASE = "https://app.example.com";
	private static final String TOKEN = "6f1c2d3e-0000-4000-8000-000000000001";

	private final TrackingHtmlRewriter rewriter = new TrackingHtmlRewriter(
		new TrackingProperties(BASE, 10, List.of("bot"), "salt", 1));

	/** 호출된 원본 URL 을 기록하고, 처음 보는 URL 에 1, 2, 3... 을 배정하는 resolver */
	private static class Recorder implements Function<String, Long> {
		final Map<String, Long> ids = new HashMap<>();
		final List<String> asked = new ArrayList<>();

		@Override
		public Long apply(String url) {
			asked.add(url);
			return ids.computeIfAbsent(url, u -> (long) ids.size() + 1);
		}
	}

	private String pixel() {
		return "<img src=\"" + BASE + "/t/o/" + TOKEN + ".gif\" width=\"1\" height=\"1\" alt=\"\" style=\"display:none\">";
	}

	@Test
	void 일반_링크는_추적_URL로_바뀌고_본문_끝에_픽셀이_들어간다() {
		Recorder resolver = new Recorder();

		String result = rewriter.rewrite("<p><a href=\"https://shop.example.com/sale\">보기</a></p>", TOKEN, resolver);

		assertThat(result).isEqualTo("<p><a href=\"" + BASE + "/t/c/" + TOKEN + "/1\">보기</a></p>" + pixel());
		assertThat(resolver.asked).containsExactly("https://shop.example.com/sale");
	}

	@Test
	void 픽셀은_body_닫는_태그_앞에_들어간다() {
		String result = rewriter.rewrite("<html><body><p>안녕</p></BODY></html>", TOKEN, new Recorder());

		assertThat(result).isEqualTo("<html><body><p>안녕</p>" + pixel() + "</BODY></html>");
	}

	@Test
	void 링크가_없어도_픽셀은_들어간다() {
		assertThat(rewriter.rewrite("<p>안녕</p>", TOKEN, new Recorder())).isEqualTo("<p>안녕</p>" + pixel());
	}

	@Test
	void 수신거부_링크는_바꾸지_않는다() {
		Recorder resolver = new Recorder();
		String html = "<a href=\"" + BASE + "/unsubscribe/abc123\">수신거부</a>"
			+ "<a href=\"" + BASE + "/api/v1/unsubscribe/one-click/abc123\">원클릭</a>";

		String result = rewriter.rewrite(html, TOKEN, resolver);

		assertThat(result).isEqualTo(html + pixel());
		assertThat(resolver.asked).isEmpty();
	}

	@Test
	void 쿠폰_링크는_바꾸지_않는다() {
		Recorder resolver = new Recorder();
		String html = "<a href=\"" + BASE + "/c/0b9f6d1e-1111-4222-8333-444455556666\">쿠폰 받기</a>";

		assertThat(rewriter.rewrite(html, TOKEN, resolver)).isEqualTo(html + pixel());
		assertThat(resolver.asked).isEmpty();
	}

	@Test
	void mailto_tel_해시_상대경로_javascript는_바꾸지_않는다() {
		Recorder resolver = new Recorder();
		String html = "<a href=\"mailto:cs@example.com\">메일</a><a href=\"tel:020000000\">전화</a>"
			+ "<a href=\"#top\">위로</a><a href=\"/relative\">상대</a><a href=\"javascript:void(0)\">js</a><a href=\"\">빈 링크</a>";

		assertThat(rewriter.rewrite(html, TOKEN, resolver)).isEqualTo(html + pixel());
		assertThat(resolver.asked).isEmpty();
	}

	@Test
	void 이미_추적_URL이면_다시_바꾸지_않는다() {
		Recorder resolver = new Recorder();
		String html = "<a href=\"" + BASE + "/t/c/" + TOKEN + "/7\">이미 추적</a>";

		assertThat(rewriter.rewrite(html, TOKEN, resolver)).isEqualTo(html + pixel());
		assertThat(resolver.asked).isEmpty();
	}

	@Test
	void 우리_서비스가_아닌_곳의_c나_unsubscribe_경로는_일반_링크다() {
		Recorder resolver = new Recorder();

		rewriter.rewrite("<a href=\"https://other.example.com/c/1\">a</a><a href=\"https://other.example.com/unsubscribe/x\">b</a>",
			TOKEN, resolver);

		assertThat(resolver.asked).containsExactly("https://other.example.com/c/1", "https://other.example.com/unsubscribe/x");
	}

	@Test
	void 일반_링크와_제외_링크가_섞여_있으면_일반_링크만_바뀐다() {
		Recorder resolver = new Recorder();
		String html = "<a href=\"https://shop.example.com/a\">A</a>"
			+ "<a href=\"" + BASE + "/c/6f1c\">쿠폰</a>"
			+ "<a href=\"https://shop.example.com/b\">B</a>"
			+ "<a href=\"" + BASE + "/unsubscribe/t\">수신거부</a>";

		String result = rewriter.rewrite(html, TOKEN, resolver);

		assertThat(result).isEqualTo("<a href=\"" + BASE + "/t/c/" + TOKEN + "/1\">A</a>"
			+ "<a href=\"" + BASE + "/c/6f1c\">쿠폰</a>"
			+ "<a href=\"" + BASE + "/t/c/" + TOKEN + "/2\">B</a>"
			+ "<a href=\"" + BASE + "/unsubscribe/t\">수신거부</a>" + pixel());
		assertThat(resolver.asked).containsExactly("https://shop.example.com/a", "https://shop.example.com/b");
	}

	@Test
	void 같은_URL이_여러_번_나오면_같은_linkId를_쓴다() {
		String result = rewriter.rewrite("<a href=\"https://s.example.com/x\">1</a><a href=\"https://s.example.com/x\">2</a>",
			TOKEN, new Recorder());

		assertThat(result).contains(BASE + "/t/c/" + TOKEN + "/1\">1</a>").contains(BASE + "/t/c/" + TOKEN + "/1\">2</a>");
		assertThat(result).doesNotContain("/2\"");
	}

	@Test
	void href의_HTML_엔티티는_풀어서_원본_URL로_조회한다() {
		Recorder resolver = new Recorder();

		rewriter.rewrite("<a href=\"https://s.example.com/p?a=1&amp;b=2\">링크</a>", TOKEN, resolver);

		assertThat(resolver.asked).containsExactly("https://s.example.com/p?a=1&b=2");
	}

	@Test
	void 따옴표_종류와_다른_속성_순서를_처리하고_다른_속성은_보존한다() {
		String result = rewriter.rewrite(
			"<a class='btn' target=\"_blank\" href='https://s.example.com/x' style=\"color:red\">단일</a>"
				+ "<A HREF=https://s.example.com/y>따옴표없음</A>", TOKEN, new Recorder());

		assertThat(result).startsWith("<a class='btn' target=\"_blank\" href=\"" + BASE + "/t/c/" + TOKEN + "/1\" style=\"color:red\">단일</a>");
		assertThat(result).contains("<A HREF=\"" + BASE + "/t/c/" + TOKEN + "/2\">따옴표없음</A>");
	}

	@Test
	void data_href_같은_비슷한_속성은_건드리지_않는다() {
		Recorder resolver = new Recorder();
		String html = "<a data-href=\"https://s.example.com/x\" title=\"x\">링크</a>";

		assertThat(rewriter.rewrite(html, TOKEN, resolver)).isEqualTo(html + pixel());
		assertThat(resolver.asked).isEmpty();
	}

	@Test
	void 주석_안의_링크는_바꾸지_않는다() {
		Recorder resolver = new Recorder();
		String html = "<!-- <a href=\"https://s.example.com/hidden\">숨김</a> --><a href=\"https://s.example.com/shown\">보임</a>";

		String result = rewriter.rewrite(html, TOKEN, resolver);

		assertThat(result).startsWith("<!-- <a href=\"https://s.example.com/hidden\">숨김</a> -->");
		assertThat(resolver.asked).containsExactly("https://s.example.com/shown");
	}

	@Test
	void resolver가_null을_돌려주면_그_링크는_원문_그대로다() {
		String html = "<a href=\"https://s.example.com/x\">링크</a>";

		assertThat(rewriter.rewrite(html, TOKEN, url -> null)).isEqualTo(html + pixel());
	}

	@Test
	void 이미_픽셀이_있으면_중복_삽입하지_않는다() {
		String once = rewriter.rewrite("<p>안녕</p>", TOKEN, new Recorder());

		assertThat(rewriter.rewrite(once, TOKEN, new Recorder())).isEqualTo(once);
	}

	@Test
	void 기준_주소_끝의_슬래시와_대소문자에_상관없이_제외_경로를_판단한다() {
		TrackingHtmlRewriter slashBase = new TrackingHtmlRewriter(
			new TrackingProperties(BASE + "/", 10, List.of(), "", 1));
		Recorder resolver = new Recorder();

		String result = slashBase.rewrite("<a href=\"" + BASE.toUpperCase() + "/C/abc\">쿠폰</a><a href=\"https://s.example.com/x\">일반</a>",
			TOKEN, resolver);

		assertThat(resolver.asked).containsExactly("https://s.example.com/x");
		assertThat(result).contains(BASE + "/t/c/" + TOKEN + "/1");
		assertThat(result).doesNotContain(BASE + "//");
	}

	@Test
	void html이_null이거나_비어_있으면_그대로_돌려준다() {
		assertThat(rewriter.rewrite(null, TOKEN, new Recorder())).isNull();
		assertThat(rewriter.rewrite("  ", TOKEN, new Recorder())).isEqualTo("  ");
	}

	@Test
	void 추적_URL에는_순번_ID가_아닌_UUID_토큰만_쓴다() {
		String result = rewriter.rewrite("<a href=\"https://s.example.com/x\">링크</a>", TOKEN, new Recorder());

		assertThat(result).contains("/t/c/" + TOKEN + "/").contains("/t/o/" + TOKEN + ".gif");
	}
}
