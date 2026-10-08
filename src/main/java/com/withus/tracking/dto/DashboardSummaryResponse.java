package com.withus.tracking.dto;

import java.time.LocalDate;

/** GET /dashboard/summary — 기간(from~to, 한국 시간 날짜, 양 끝 포함)의 KPI */
public record DashboardSummaryResponse(LocalDate from, LocalDate to, SendKpi kpi) {
}
