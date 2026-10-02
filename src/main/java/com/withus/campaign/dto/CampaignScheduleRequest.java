package com.withus.campaign.dto;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotNull;

public record CampaignScheduleRequest(@NotNull(message = "예약 시각을 입력하세요.") OffsetDateTime scheduledAt) {
}
