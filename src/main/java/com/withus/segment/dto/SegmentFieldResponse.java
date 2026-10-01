package com.withus.segment.dto;

import java.util.Arrays;
import java.util.List;

import com.withus.segment.domain.SegmentField;
import com.withus.segment.domain.SegmentOperator;

import io.swagger.v3.oas.annotations.media.Schema;

/** 빌더에서 고를 수 있는 필드와 연산자 (API_SPEC 4장 GET /segments/fields). 서버 화이트리스트 그대로 */
public record SegmentFieldResponse(@Schema(example = "region") String field, List<SegmentOperator> operators) {

	public static List<SegmentFieldResponse> all() {
		return Arrays.stream(SegmentField.values())
			.map(f -> new SegmentFieldResponse(f.key(), f.operators()))
			.toList();
	}
}
