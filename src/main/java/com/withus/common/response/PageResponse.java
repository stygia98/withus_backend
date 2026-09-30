package com.withus.common.response;

import java.util.List;

/**
 * 페이징 응답의 data 형식 (API_SPEC 1.2). page 는 0부터
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

	public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
		int totalPages = size == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
		return new PageResponse<>(content, page, size, totalElements, totalPages);
	}
}
