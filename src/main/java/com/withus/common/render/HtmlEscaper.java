package com.withus.common.render;

import java.util.HashMap;
import java.util.Map;

/** HTML 본문에 넣을 값의 이스케이프. 외부 라이브러리 없이 HTML 에서 의미 있는 5개 문자만 바꾼다 */
public final class HtmlEscaper {

	private HtmlEscaper() {
	}

	/** & < > " ' 를 엔티티로 바꾼다. 따옴표도 바꾸므로 href="..." 같은 속성 값에 넣어도 안전하다 */
	public static String escape(String value) {
		if (value == null || value.isEmpty()) {
			return value;
		}
		StringBuilder escaped = null;
		for (int i = 0; i < value.length(); i++) {
			String replacement = switch (value.charAt(i)) {
				case '&' -> "&amp;";
				case '<' -> "&lt;";
				case '>' -> "&gt;";
				case '"' -> "&quot;";
				case '\'' -> "&#39;";
				default -> null;
			};
			if (replacement != null && escaped == null) {
				escaped = new StringBuilder(value.length() + 16).append(value, 0, i);
			}
			if (escaped != null) {
				escaped.append(replacement != null ? replacement : String.valueOf(value.charAt(i)));
			}
		}
		return escaped == null ? value : escaped.toString();
	}

	/** 모든 값을 이스케이프한 새 맵을 돌려준다. 입력 맵은 바꾸지 않으며 null 값은 그대로 둔다 */
	public static Map<String, String> escapeValues(Map<String, String> values) {
		if (values == null) {
			return null;
		}
		Map<String, String> escaped = new HashMap<>(values.size() * 2);
		values.forEach((key, value) -> escaped.put(key, escape(value)));
		return escaped;
	}
}
