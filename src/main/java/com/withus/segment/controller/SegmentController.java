package com.withus.segment.controller;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.withus.auth.security.AuthMember;
import com.withus.common.response.ApiResponse;
import com.withus.common.response.PageResponse;
import com.withus.segment.dto.SegmentFieldResponse;
import com.withus.segment.dto.SegmentPreviewRequest;
import com.withus.segment.dto.SegmentPreviewResponse;
import com.withus.segment.dto.SegmentRequest;
import com.withus.segment.dto.SegmentResponse;
import com.withus.segment.service.SegmentServiceImpl;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "세그먼트", description = "조건 기반 동적 세그먼트 (API_SPEC 4장, docs/plans/segment-sql.md)")
@RestController
@RequestMapping("/api/v1/segments")
@RequiredArgsConstructor
public class SegmentController {

	private static final String RULE_ERRORS = "오류: SEGMENT_INVALID_RULE(400, error.details.path 에 문제 위치), "
		+ "SEGMENT_TOO_MANY_CONDITIONS(400, 조건 합계 10개 초과)";

	private final SegmentServiceImpl segmentService;

	@Operation(summary = "목록", description = "최신순, 세그먼트마다 현재 대상 수 포함. STAFF 는 조회만 (PRD 3장)")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER','STAFF')")
	@GetMapping
	public ApiResponse<PageResponse<SegmentResponse>> list(@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "20") int size) {
		return ApiResponse.ok(segmentService.list(page, size));
	}

	@Operation(summary = "상세", description = "rule 포함, 현재 대상 수 포함")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER','STAFF')")
	@GetMapping("/{segmentId}")
	public ApiResponse<SegmentResponse> get(@PathVariable long segmentId) {
		return ApiResponse.ok(segmentService.get(segmentId));
	}

	@Operation(summary = "생성", description = "조건과 연산자는 GET /segments/fields (DB_SCHEMA 5.1). age 는 만 나이, "
		+ "joinedAt IN_LAST_DAYS 는 오늘 포함 N일, region NE 는 지역이 비어 있는 고객 제외. " + RULE_ERRORS)
	@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
	@PostMapping
	public ApiResponse<SegmentResponse> create(@Valid @RequestBody SegmentRequest request,
		@AuthenticationPrincipal AuthMember me) {
		return ApiResponse.ok(segmentService.create(request, me.memberId()));
	}

	@Operation(summary = "수정", description = "이름·설명·조건을 통째로 바꾼다. " + RULE_ERRORS)
	@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
	@PutMapping("/{segmentId}")
	public ApiResponse<SegmentResponse> update(@PathVariable long segmentId,
		@Valid @RequestBody SegmentRequest request) {
		return ApiResponse.ok(segmentService.update(segmentId, request));
	}

	@Operation(summary = "삭제", description = "캠페인이 참조 중이면 SEGMENT_IN_USE(409)")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
	@DeleteMapping("/{segmentId}")
	public ApiResponse<Void> delete(@PathVariable long segmentId) {
		segmentService.delete(segmentId);
		return ApiResponse.ok(null);
	}

	@Operation(summary = "사용 가능한 필드·연산자", description = "서버 화이트리스트 그대로 (DB_SCHEMA 5.1)")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER','STAFF')")
	@GetMapping("/fields")
	public ApiResponse<List<SegmentFieldResponse>> fields() {
		return ApiResponse.ok(SegmentFieldResponse.all());
	}

	@Operation(summary = "대상 수 미리보기", description = "저장하지 않는다. 빌더에서 디바운스 500ms 로 호출 (PRD F-03). "
		+ RULE_ERRORS)
	@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
	@PostMapping("/preview")
	public ApiResponse<SegmentPreviewResponse> preview(@Valid @RequestBody SegmentPreviewRequest request) {
		return ApiResponse.ok(segmentService.preview(request.rule()));
	}
}
