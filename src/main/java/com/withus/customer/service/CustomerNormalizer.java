package com.withus.customer.service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

import com.withus.common.exception.BusinessException;
import com.withus.common.normalize.Emails;
import com.withus.customer.domain.CustomerErrorCode;
import com.withus.customer.domain.Region;

/**
 * 입력값 정규화 (PRD F-01, CLAUDE.md 6장 9번). 개별 등록·업로드·suppression 비교가 모두 이 함수를 쓴다
 * 필수 여부는 요청 DTO 검증이 맡고, 여기서는 정규화와 형식만 본다
 */
public final class CustomerNormalizer {

	// TODO(PL 확인): 유선번호 허용 여부. 지금은 휴대폰(01로 시작 10~11자리)만
	private static final Pattern MOBILE = Pattern.compile("01\\d{8,9}");

	private CustomerNormalizer() {
	}

	/** 소문자 + 앞뒤 공백 제거. 관리자 계정과 같은 규칙을 쓰도록 공통 기준에 맡긴다 */
	public static String email(String value) {
		return Emails.normalize(value);
	}

	/** 숫자만 남긴다. 빈 값은 null */
	public static String phone(String value) {
		if (isBlank(value)) {
			return null;
		}
		String digits = value.replaceAll("\\D", "");
		if (!MOBILE.matcher(digits).matches()) {
			throw new BusinessException(CustomerErrorCode.CUSTOMER_INVALID_PHONE);
		}
		return digits;
	}

	/** 시·도명 또는 코드 → 코드(SEOUL 등). 빈 값은 null */
	public static String regionCode(String value) {
		return isBlank(value) ? null : Region.from(value).name();
	}

	/** YYYY-MM-DD 만 허용(없는 날짜 거부). 빈 값은 null */
	public static LocalDate date(String value) {
		if (isBlank(value)) {
			return null;
		}
		try {
			return LocalDate.parse(value.trim());
		} catch (DateTimeParseException e) {
			throw new BusinessException(CustomerErrorCode.CUSTOMER_INVALID_DATE);
		}
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
