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

	// ---- evaluateBulk (캠페인 3/4 estimate·schedule·start 의 20:50 컷오프) ----

	@Test
	void 저녁_21시_예약은_막히고_다음날_08시가_안내된다() {
		SendWindow.BulkWindowResult result = sendWindow.evaluateBulk(at(21, 0), 60);

		assertThat(result.allowed()).isFalse();
		assertThat(result.nextAvailableAt()).isEqualTo(
			OffsetDateTime.of(2026, 10, 2, 8, 0, 0, 0, ZoneOffset.ofHours(9)));
	}

	@Test
	void 시작은_창_안이어도_대기분_때문에_20시50분을_넘기면_막힌다() {
		// 19:00 시작 + 2시간(7200초) = 21:00 종료 → 20:50 을 넘김
		SendWindow.BulkWindowResult result = sendWindow.evaluateBulk(at(19, 0), 2 * 3600);

		assertThat(result.allowed()).isFalse();
		assertThat(result.expectedEndAt()).isEqualTo(at(21, 0));
		assertThat(result.nextAvailableAt()).isEqualTo(
			OffsetDateTime.of(2026, 10, 2, 8, 0, 0, 0, ZoneOffset.ofHours(9)));
	}

	@Test
	void 종료가_20시50분_정각이면_허용된다() {
		SendWindow.BulkWindowResult result = sendWindow.evaluateBulk(at(19, 50), 3600);

		assertThat(result.allowed()).isTrue();
		assertThat(result.expectedEndAt()).isEqualTo(at(20, 50));
		assertThat(result.nextAvailableAt()).isNull();
	}

	@Test
	void 창_시작_전이면_08시로_당겨서_계산한다() {
		// 07:00 시작 요청 → 08:00 으로 당겨짐 + 10분 = 08:10 종료, 창 안이라 허용
		SendWindow.BulkWindowResult result = sendWindow.evaluateBulk(at(7, 0), 600);

		assertThat(result.allowed()).isTrue();
		assertThat(result.expectedEndAt()).isEqualTo(at(8, 10));
	}

	@Test
	void evaluateBulk는_요청_오프셋과_무관하게_서울_시각으로_판정한다() {
		// 2026-10-05T11:50:00Z = 서울 20:50 — 다른 오프셋이어도 같은 순간이면 같은 결과
		OffsetDateTime utc = OffsetDateTime.of(2026, 10, 5, 11, 50, 1, 0, ZoneOffset.UTC); // 서울 20:50:01
		OffsetDateTime seoul = utc.withOffsetSameInstant(ZoneOffset.ofHours(9));
		OffsetDateTime minus5 = utc.withOffsetSameInstant(ZoneOffset.ofHours(-5));

		assertThat(sendWindow.evaluateBulk(utc, 0).allowed()).as("Z 로 온 서울 20:50:01").isFalse();
		assertThat(sendWindow.evaluateBulk(minus5, 0).allowed()).as("-05:00 으로 온 서울 20:50:01").isFalse();
		assertThat(sendWindow.evaluateBulk(seoul, 0).allowed()).isFalse();
	}

	@Test
	void evaluateBulk는_UTC_0시를_서울_09시로_본다() {
		// 서울 09:00 은 창 안이므로 08:00 으로 당겨지지 않는다
		OffsetDateTime utcMidnight = OffsetDateTime.of(2026, 10, 5, 0, 0, 0, 0, ZoneOffset.UTC);

		SendWindow.BulkWindowResult result = sendWindow.evaluateBulk(utcMidnight, 60);

		assertThat(result.allowed()).isTrue();
		assertThat(result.expectedEndAt().toInstant()).isEqualTo(utcMidnight.plusSeconds(60).toInstant());
	}

	@Test
	void evaluateBulk_종료_시각이_정확히_20시50분이면_통과하고_1초_넘기면_막는다() {
		OffsetDateTime start = at(20, 0); // 20:00:00
		assertThat(sendWindow.evaluateBulk(start, 50 * 60).allowed()).as("종료 20:50:00").isTrue();
		assertThat(sendWindow.evaluateBulk(start, 50 * 60 + 1).allowed()).as("종료 20:50:01").isFalse();
	}

	@Test
	void holdUntil도_오프셋과_무관하게_서울_시각으로_판정한다() {
		OffsetDateTime utc = OffsetDateTime.of(2026, 10, 5, 11, 50, 1, 0, ZoneOffset.UTC); // 서울 20:50:01

		assertThat(sendWindow.holdUntil(utc)).isPresent();
	}
}
