package com.withus.campaign.dto;

import jakarta.validation.constraints.NotNull;

/** segmentId 가 있으면 그 세그먼트 대상 중 기본값으로 나갈 인원도 계산한다 */
public record TemplatePreviewRequest(@NotNull(message = "sampleCustomerId 를 입력하세요.") Long sampleCustomerId,
		Long segmentId) {
}
