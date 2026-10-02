package com.withus.common.privacy;

/** 고객 공개 페이지(쿠폰 /c, 수신거부 /unsubscribe) 공통 개인정보 마스킹 */
public final class Masking {

	private Masking() {
	}

	/** 첫 글자만 남기고 나머지는 * (코드 포인트 기준이라 한글·이모지도 안전). 비어 있으면 null */
	public static String maskName(String name) {
		if (name == null || name.isBlank()) {
			return null;
		}
		String trimmed = name.strip();
		int first = trimmed.offsetByCodePoints(0, 1);
		int rest = trimmed.codePointCount(first, trimmed.length());
		return trimmed.substring(0, first) + "*".repeat(rest);
	}
}
