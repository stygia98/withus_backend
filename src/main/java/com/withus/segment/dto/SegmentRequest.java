package com.withus.segment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/** 세그먼트 생성 (API_SPEC 4장). rule 형식은 DB_SCHEMA 5.1 */
public record SegmentRequest(
	@NotBlank(message = "이름을 입력하세요.") @Size(max = 100) String name,
	@Size(max = 500) String description,
	@NotNull(message = "조건을 입력하세요.") @Schema(description = "DB_SCHEMA 5.1 rule_json 형식",
		example = "{\"operator\":\"AND\",\"groups\":[{\"operator\":\"AND\",\"conditions\":["
			+ "{\"field\":\"region\",\"op\":\"IN\",\"value\":[\"SEOUL\",\"GYEONGGI\"]},"
			+ "{\"field\":\"totalPurchase\",\"op\":\"GTE\",\"value\":100000}]}]}") JsonNode rule) {
}
