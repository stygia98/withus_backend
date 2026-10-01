package com.withus.tracking.service;

import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.withus.tracking.config.TrackingProperties;

/**
 * 완성된 메일 HTML 의 링크를 추적 URL 로 바꾸고 오픈 픽셀을 넣는 순수 변환기 (PRD 8.1).
 * DB 를 모른다 — 원본 URL 에 대응하는 linkId 는 호출하는 쪽이 resolver 로 알려 준다.
 *
 * <p>바꾸지 않는 링크: 수신거부(/unsubscribe/, /api/v1/unsubscribe/), 쿠폰(/c/), 이미 추적 URL(/t/),
 * mailto:, tel: 등 http/https 가 아닌 링크, 주석 안의 링크. 쿠폰 링크는 고객마다 주소가 달라 track_link 에 담을 수 없다.
 */
@Component
public class TrackingHtmlRewriter {

	/** 추적에서 제외하는 우리 서비스의 경로 (기준 주소 뒤) */
	private static final String[] EXCLUDED_PATHS = { "/unsubscribe/", "/api/v1/unsubscribe/", "/c/", "/t/" };

	/** 주석은 그대로 통과시키고, <a ... href=값> 에서 값만 잡는다. href 앞에는 공백이 있어야 한다(data-href 제외) */
	private static final Pattern ANCHOR = Pattern.compile(
		"<!--.*?-->|(<a\\b[^>]*?\\shref\\s*=\\s*)(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))",
		Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	private final String baseUrl;

	public TrackingHtmlRewriter(TrackingProperties properties) {
		String base = properties.baseUrl() == null ? "" : properties.baseUrl().trim();
		this.baseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
	}

	/**
	 * @param html          치환·광고 문구 삽입까지 끝난 완성된 HTML
	 * @param trackingToken send_log.tracking_token (UUID 문자열)
	 * @param linkIds       디코딩된 원본 URL → track_link.link_id. null 을 돌려주면 그 링크는 바꾸지 않는다.
	 *                      문서에 나온 순서대로 호출된다
	 */
	public String rewrite(String html, String trackingToken, Function<String, Long> linkIds) {
		if (html == null || html.isBlank()) {
			return html;
		}
		Matcher matcher = ANCHOR.matcher(html);
		StringBuilder result = new StringBuilder(html.length() + 128);
		while (matcher.find()) {
			String prefix = matcher.group(1);
			String replacement = matcher.group(); // 주석이거나 바꾸지 않는 링크는 원문 그대로
			if (prefix != null) {
				String rawHref = firstNonNull(matcher.group(2), matcher.group(3), matcher.group(4));
				String url = htmlUnescape(rawHref).trim();
				if (isTrackable(url)) {
					Long linkId = linkIds.apply(url);
					if (linkId != null) {
						replacement = prefix + "\"" + trackUrl(trackingToken, linkId) + "\"";
					}
				}
			}
			matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(result);
		return insertPixel(result.toString(), trackingToken);
	}

	/** 추적 대상인가: 우리 서비스의 수신거부·쿠폰·추적 주소가 아닌 http/https 링크 */
	boolean isTrackable(String url) {
		String lower = url.toLowerCase(Locale.ROOT);
		if (!(lower.startsWith("http://") || lower.startsWith("https://"))) {
			return false;
		}
		String lowerBase = baseUrl.toLowerCase(Locale.ROOT);
		if (lowerBase.isEmpty()) {
			return true;
		}
		for (String path : EXCLUDED_PATHS) {
			if (lower.startsWith(lowerBase + path)) {
				return false;
			}
		}
		return true;
	}

	private String trackUrl(String token, long linkId) {
		return baseUrl + "/t/c/" + token + "/" + linkId;
	}

	/** 본문 끝(</body> 앞, 없으면 맨 끝)에 1x1 픽셀을 넣는다. 이미 들어 있으면 다시 넣지 않는다 */
	private String insertPixel(String html, String token) {
		String pixelUrl = baseUrl + "/t/o/" + token + ".gif";
		if (html.contains(pixelUrl)) {
			return html;
		}
		String pixel = "<img src=\"" + pixelUrl + "\" width=\"1\" height=\"1\" alt=\"\" style=\"display:none\">";
		int bodyEnd = html.toLowerCase(Locale.ROOT).lastIndexOf("</body>");
		return bodyEnd < 0 ? html + pixel : html.substring(0, bodyEnd) + pixel + html.substring(bodyEnd);
	}

	/** href 속성의 HTML 엔티티를 풀어 실제 URL 로 만든다. &amp; 는 이중 해제를 피하려고 마지막에 푼다 */
	static String htmlUnescape(String value) {
		return value.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
			.replace("&#39;", "'").replace("&#x27;", "'").replace("&amp;", "&");
	}

	private static String firstNonNull(String... values) {
		for (String value : values) {
			if (value != null) {
				return value;
			}
		}
		return "";
	}
}
