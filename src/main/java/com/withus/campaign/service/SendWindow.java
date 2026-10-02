package com.withus.campaign.service;

import java.time.Clock;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * 광고성·NOTICE 발송 시간창(withus.send-window, PRD 8.4) 판정 (발송 큐 Plan 8장, 15장 B2).
 * 08:00 이전이면 오늘 08:00, 20:50 이후면 다음 날 08:00 으로 보류한다. 경계(08:00·20:50)는 창 안이다.
 */
public class SendWindow {

	private final LocalTime start;
	private final LocalTime end;
	private final Clock clock;

	public SendWindow(LocalTime start, LocalTime end) {
		this(start, end, Clock.system(ZoneId.of("Asia/Seoul")));
	}

	/** 단위 테스트에서 가짜 시계를 주입할 수 있게 열어둔 생성자 */
	public SendWindow(LocalTime start, LocalTime end, Clock clock) {
		this.start = start;
		this.end = end;
		this.clock = clock;
	}

	/** 지금 보낼 수 있으면 비어있는 Optional, 아니면 보류할 next_attempt_at */
	public Optional<OffsetDateTime> holdUntil() {
		return holdUntil(OffsetDateTime.now(clock));
	}

	/** 판정만 떼어낸 순수 메서드 — 경계값 단위 테스트용 */
	public Optional<OffsetDateTime> holdUntil(OffsetDateTime now) {
		now = inWindowZone(now);
		LocalTime time = now.toLocalTime();
		if (!time.isBefore(start) && !time.isAfter(end)) {
			return Optional.empty();
		}
		boolean beforeWindow = time.isBefore(start);
		return Optional.of(OffsetDateTime.of(
			beforeWindow ? now.toLocalDate() : now.toLocalDate().plusDays(1),
			start, now.getOffset()));
	}

	/**
	 * 캠페인 시작·예약 시 쓰는 순수 메서드(Clock 없이 startAt 을 그대로 받는다, 캠페인 3/4).
	 * startAt 이 이미 오늘 창을 넘긴 뒤(20:50 초과)면 당겨주지 않고 바로 막는다 — 10.3 "21시에 광고
	 * 메일을 예약하려 하면 막히고"는 "내일로 자동 연기"가 아니라 거절을 요구한다.
	 * startAt 이 창이 열리기 전(08:00 이전)이면 오늘 08:00 으로 당겨서 계산한다(holdUntil 과 같은 규칙,
	 * 다만 당기는 건 같은 날 안에서만 — 밤 시간대 요청을 다음 날로 몰래 넘기지 않는다).
	 * 당긴 뒤에도 durationSeconds 가 지나 끝나는 시각이 20:50 을 넘기면 막는다
	 * (PRD 8.4 "20:50 을 넘겨 끝날 예약은 막는다").
	 */
	public BulkWindowResult evaluateBulk(OffsetDateTime startAt, long durationSeconds) {
		startAt = inWindowZone(startAt);
		LocalTime startTime = startAt.toLocalTime();
		if (startTime.isAfter(end)) {
			return new BulkWindowResult(startAt.plusSeconds(durationSeconds), false, nextDayStart(startAt));
		}
		OffsetDateTime effectiveStart = startTime.isBefore(start)
			? OffsetDateTime.of(startAt.toLocalDate(), start, startAt.getOffset())
			: startAt;
		OffsetDateTime expectedEndAt = effectiveStart.plusSeconds(durationSeconds);
		boolean sameDay = expectedEndAt.toLocalDate().equals(effectiveStart.toLocalDate());
		boolean withinEnd = !expectedEndAt.toLocalTime().isAfter(end);
		boolean allowed = sameDay && withinEnd;
		OffsetDateTime nextAvailableAt = allowed ? null : nextDayStart(effectiveStart);
		return new BulkWindowResult(expectedEndAt, allowed, nextAvailableAt);
	}

	/**
	 * toLocalTime() 은 값에 붙은 오프셋의 시각을 그대로 돌려주므로, 요청이 Z·-05:00 으로 와도 서울 시각으로 바꾼 뒤 판정한다
	 * (PR #31 리뷰 🔴2). 같은 순간이면 어느 오프셋으로 와도 같은 결과여야 한다
	 */
	private OffsetDateTime inWindowZone(OffsetDateTime time) {
		return time.atZoneSameInstant(clock.getZone()).toOffsetDateTime();
	}

	private OffsetDateTime nextDayStart(OffsetDateTime from) {
		return OffsetDateTime.of(from.toLocalDate().plusDays(1), start, from.getOffset());
	}

	public record BulkWindowResult(OffsetDateTime expectedEndAt, boolean allowed, OffsetDateTime nextAvailableAt) {
	}
}
