package com.withus.common.render;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

/**
 * 치환자 렌더러 (PRD F-04). 값은 고객 값 → 템플릿 기본값({{name|고객}}) → 시스템 기본값 순으로 정한다.
 *
 * <ul>
 * <li>"값이 없다"는 null 과 공백뿐인 문자열을 뜻한다. 빈 문자열이 그대로 나가지 않게 하기 위해서다.</li>
 * <li>템플릿을 한 번만 훑는다. 치환된 값 안에 {{...}} 가 있어도 다시 치환하지 않는다.</li>
 * <li>지원하지 않는 치환자({{foo}})는 그대로 둔다. 잘못된 치환자는 템플릿 저장 시 TEMPLATE_INVALID_PLACEHOLDER 로 막는다.</li>
 * <li>HTML 이스케이프는 하지 않는다. 호출하는 쪽이 본문(HTML)·제목·SMS 중 무엇을 렌더링하는지 알고 있다.</li>
 * </ul>
 */
@Service
public class DefaultPlaceholderRenderer implements PlaceholderRenderer {

	/**
	 * 치환자별 시스템 기본값. 이름·지역·누적구매액은 PRD F-04 에 명시돼 있다.
	 * email·couponUrl 은 PRD 에 기본값이 없어 빈 값으로 둔다 (확인 필요 사항).
	 */
	static final Map<String, String> SYSTEM_DEFAULTS = Map.of(
		"name", "고객",
		"region", "",
		"totalPurchase", "0",
		"email", "",
		"couponUrl", "");

	/** {{name}}, {{ name }}, {{name|기본값}}. 기본값에는 중괄호를 쓸 수 없다 */
	private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9]*)\\s*(?:\\|([^{}]*))?\\}\\}");

	@Override
	public String render(String template, Map<String, String> values) {
		if (template == null) {
			return "";
		}
		Matcher matcher = PLACEHOLDER.matcher(template);
		StringBuilder result = new StringBuilder(template.length());
		while (matcher.find()) {
			String replacement = isSupported(matcher.group(1))
				? resolve(matcher.group(1), matcher.group(2), values).value()
				: matcher.group(); // 지원하지 않는 치환자는 원문 그대로
			matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(result);
		return result.toString();
	}

	@Override
	public boolean usesDefault(String template, Map<String, String> values) {
		if (template == null) {
			return false;
		}
		Matcher matcher = PLACEHOLDER.matcher(template);
		while (matcher.find()) {
			if (isSupported(matcher.group(1)) && resolve(matcher.group(1), matcher.group(2), values).defaulted()) {
				return true;
			}
		}
		return false;
	}

	private static boolean isSupported(String name) {
		return SYSTEM_DEFAULTS.containsKey(name);
	}

	private static Resolved resolve(String name, String templateDefault, Map<String, String> values) {
		String customerValue = values == null ? null : values.get(name);
		if (hasText(customerValue)) {
			return new Resolved(customerValue, false);
		}
		if (hasText(templateDefault)) {
			return new Resolved(templateDefault.trim(), true);
		}
		return new Resolved(SYSTEM_DEFAULTS.get(name), true);
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	/** @param defaulted 고객 값이 없어 기본값(템플릿 또는 시스템)으로 대체됐는가 */
	private record Resolved(String value, boolean defaulted) {
	}
}
