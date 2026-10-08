package com.withus.ai.service;

import java.util.regex.Pattern;

import com.withus.ai.domain.AiErrorCode;
import com.withus.common.exception.BusinessException;

/**
 * LLM 으로 나가는 텍스트에서 개인정보 패턴(이메일·전화번호)을 찾아 막는다 (CLAUDE.md 6장 12번).
 * 이름은 패턴으로 알 수 없으므로 호출하는 쪽이 집계값·익명 데이터만 넘기는 것이 기본 전제이고,
 * 이 검사는 실수로 원본 값이 섞이는 것을 막는 마지막 방어선이다.
 */
final class PiiGuard {

	private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

	/** 휴대폰(010 등)·지역번호·대표번호, 하이픈·점·공백 구분 허용 */
	private static final Pattern PHONE = Pattern
		.compile("(?<![0-9])(?:01[016789]|0[2-6][1-5]?|070|080)[-. ]?[0-9]{3,4}[-. ]?[0-9]{4}(?![0-9])");

	private PiiGuard() {
	}

	/** 개인정보 패턴이 있으면 AI_PII_DETECTED. 오류 메시지·상세에 해당 값을 싣지 않는다 */
	static void assertNoPii(String... texts) {
		for (String text : texts) {
			if (text != null && (EMAIL.matcher(text).find() || PHONE.matcher(text).find())) {
				throw new BusinessException(AiErrorCode.AI_PII_DETECTED);
			}
		}
	}
}
