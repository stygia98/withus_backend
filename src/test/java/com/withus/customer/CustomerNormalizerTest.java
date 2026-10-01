package com.withus.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.withus.common.exception.BusinessException;
import com.withus.customer.domain.CustomerErrorCode;
import com.withus.customer.service.CustomerNormalizer;

/** 입력값 정규화 (CLAUDE.md 테스트 필수 대상). DB 없이 실행된다 */
class CustomerNormalizerTest {

	@Test
	void 이메일은_소문자와_trim() {
		assertThat(CustomerNormalizer.email(" Kim.Minji@Naver.com ")).isEqualTo("kim.minji@naver.com");
		assertThat(CustomerNormalizer.email(null)).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = { "010-1234-5678", "010 1234 5678", "01012345678", " (010)1234.5678 " })
	void 휴대폰은_숫자만(String input) {
		assertThat(CustomerNormalizer.phone(input)).isEqualTo("01012345678");
	}

	@Test
	void 휴대폰_10자리도_허용() {
		assertThat(CustomerNormalizer.phone("011-123-4567")).isEqualTo("0111234567");
	}

	@ParameterizedTest
	@ValueSource(strings = { "02-123-4567", "0101234", "010-1234-56789", "abc" })
	void 휴대폰_형식_오류(String input) {
		assertError(() -> CustomerNormalizer.phone(input), CustomerErrorCode.CUSTOMER_INVALID_PHONE);
	}

	@ParameterizedTest
	@CsvSource({ "서울, SEOUL", "서울특별시, SEOUL", "seoul, SEOUL", "' 경기 ', GYEONGGI", "강원도, GANGWON",
			"전라북도, JEONBUK", "제주특별자치도, JEJU" })
	void 지역은_코드로(String input, String expected) {
		assertThat(CustomerNormalizer.regionCode(input)).isEqualTo(expected);
	}

	@ParameterizedTest
	@ValueSource(strings = { "화성", "서울시", "SEOUL_X" })
	void 지역_오류(String input) {
		assertError(() -> CustomerNormalizer.regionCode(input), CustomerErrorCode.CUSTOMER_INVALID_REGION);
	}

	@Test
	void 날짜는_YYYY_MM_DD() {
		assertThat(CustomerNormalizer.date(" 2026-09-30 ")).isEqualTo(LocalDate.of(2026, 9, 30));
		assertThat(CustomerNormalizer.date("2024-02-29")).isEqualTo(LocalDate.of(2024, 2, 29));
	}

	@ParameterizedTest
	@ValueSource(strings = { "2026/09/30", "2026-02-30", "2026-9-30", "20260930", "2025-02-29" })
	void 날짜_오류(String input) {
		assertError(() -> CustomerNormalizer.date(input), CustomerErrorCode.CUSTOMER_INVALID_DATE);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "  " })
	void 선택_항목의_빈_값은_null(String input) {
		assertThat(CustomerNormalizer.phone(input)).isNull();
		assertThat(CustomerNormalizer.regionCode(input)).isNull();
		assertThat(CustomerNormalizer.date(input)).isNull();
	}

	private static void assertError(ThrowingCallable call, CustomerErrorCode code) {
		assertThatThrownBy(call).isInstanceOf(BusinessException.class)
			.extracting(e -> ((BusinessException) e).getErrorCode())
			.isEqualTo(code);
	}
}
