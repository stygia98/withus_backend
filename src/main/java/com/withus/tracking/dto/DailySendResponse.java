package com.withus.tracking.dto;

import java.time.LocalDate;

/** GET /dashboard/daily-sends 한 줄 — 그날의 발송 성공 건수 */
public record DailySendResponse(LocalDate date, long sent) {
}
