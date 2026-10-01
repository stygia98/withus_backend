package com.withus.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

import com.withus.coupon.domain.IssueStatus;

/** 발급 상태 계산(경계일 포함)과 고객 카드 이름 마스킹 */
class CouponStatusTest {

	private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
	private static final LocalDate TO = LocalDate.of(2026, 10, 31);

	@Test
	void 유효기간_시작일과_종료일_당일은_사용_가능() {
		assertThat(IssueStatus.of(null, FROM, TO, FROM)).isEqualTo(IssueStatus.USABLE);
		assertThat(IssueStatus.of(null, FROM, TO, TO)).isEqualTo(IssueStatus.USABLE);
	}

	@Test
	void 기간_전후() {
		assertThat(IssueStatus.of(null, FROM, TO, FROM.minusDays(1))).isEqualTo(IssueStatus.NOT_STARTED);
		assertThat(IssueStatus.of(null, FROM, TO, TO.plusDays(1))).isEqualTo(IssueStatus.EXPIRED);
	}

	@Test
	void 사용_완료는_기간이_지나도_USED() {
		assertThat(IssueStatus.of(OffsetDateTime.now(), FROM, TO, TO.plusDays(30))).isEqualTo(IssueStatus.USED);
	}
}
