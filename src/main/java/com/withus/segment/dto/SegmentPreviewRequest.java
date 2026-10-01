package com.withus.segment.dto;

import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

/** 저장하지 않고 대상 수만 계산 (API_SPEC 4장 POST /segments/preview) */
public record SegmentPreviewRequest(@NotNull(message = "조건을 입력하세요.") JsonNode rule) {
}
