package com.withus.common.normalize;

import java.util.Locale;

/**
 * 이메일 정규화 단일 기준 (CLAUDE.md 6장 9번, PRD F-01: 소문자 + 앞뒤 공백 제거).
 * 고객(등록·업로드·검색·SES 반송)과 관리자 계정(로그인·사용자 관리)이 같은 규칙을 쓴다
 */
public final class Emails {

	private Emails() {
	}

	public static String normalize(String value) {
		return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
	}
}
