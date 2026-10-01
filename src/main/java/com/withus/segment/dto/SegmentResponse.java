package com.withus.segment.dto;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

/** 세그먼트 목록·상세. 세그먼트는 동적이라 targetCount 는 조회할 때마다 다시 센다 (PRD F-03) */
public record SegmentResponse(
	long segmentId, String name, String description, JsonNode rule,
	@Schema(description = "현재 대상 고객 수") long targetCount,
	long createdBy, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
}
