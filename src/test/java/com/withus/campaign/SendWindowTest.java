package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.withus.campaign.service.SendWindow;

/** 발송 시간창 08:00~20:50 판정 (발송 큐 Plan 8장, 15장 B2) — 경계값 */
class SendWindowTest {

	private final SendWindow sendWindow = new SendWindow(LocalTime.of(8, 0), LocalTime.of(20, 50));

	private static OffsetDateTime at(int hour, int minute) {
		return OffsetDateTime.of(2026, 10, 1, hour, minute, 0, 0, ZoneOffset.ofHours(9));
	}

	@Test
	void 창_안_경계값은_보류되지_않는다() {
		assertThat(sendWindow.holdUntil(at(8, 0))).as("08:00 정각은 창 안").isEmpty();
		assertThat(sendWindow.holdUntil(at(20, 50))).as("20:50 정각은 창 안").isEmpty();
		assertThat(sendWindow.holdUntil(at(14, 0))).isEmpty();
	}

	@Test
	void 창_시작_전이면_오늘_08시로_보류한다() {
		Optional<OffsetDateTime> result = sendWindow.holdUntil(at(7, 59));

		assertThat(result).isPresent();
		assertThat(result.get()).isEqualTo(
			OffsetDateTime.of(2026, 10, 1, 8, 0, 0, 0, ZoneOffset.ofHours(9)));
	}

	@Test
	void 창_종료_후면_다음날_08시로_보류한다() {
		Optional<OffsetDateTime> result = sendWindow.holdUntil(at(20, 51));

		assertThat(result).isPresent();
		assertThat(result.get()).isEqualTo(
			OffsetDateTime.of(2026, 10, 2, 8, 0, 0, 0, ZoneOffset.ofHours(9)));
	}

	@Test
	void 이십시오십오분_광고_건은_다음날_08시로_보류한다() {
		Optional<OffsetDateTime> result = sendWindow.holdUntil(at(20, 55));

		assertThat(result).isPresent();
		assertThat(result.get()).isEqualTo(
			OffsetDateTime.of(2026, 10, 2, 8, 0, 0, 0, ZoneOffset.ofHours(9)));
	}
}
