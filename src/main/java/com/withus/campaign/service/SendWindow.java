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
		LocalTime time = now.toLocalTime();
		if (!time.isBefore(start) && !time.isAfter(end)) {
			return Optional.empty();
		}
		boolean beforeWindow = time.isBefore(start);
		return Optional.of(OffsetDateTime.of(
			beforeWindow ? now.toLocalDate() : now.toLocalDate().plusDays(1),
			start, now.getOffset()));
	}
}
