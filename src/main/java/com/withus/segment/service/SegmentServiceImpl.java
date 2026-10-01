package com.withus.segment.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.withus.common.exception.BusinessException;
import com.withus.common.exception.CommonErrorCode;
import com.withus.common.response.PageResponse;
import com.withus.segment.domain.Segment;
import com.withus.segment.domain.SegmentErrorCode;
import com.withus.segment.domain.SegmentQuery;
import com.withus.segment.dto.SegmentPreviewResponse;
import com.withus.segment.dto.SegmentRequest;
import com.withus.segment.dto.SegmentResponse;
import com.withus.segment.mapper.SegmentMapper;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 세그먼트 (PRD F-03, docs/plans/segment-sql.md). 대상은 저장하지 않고 매번 규칙으로 다시 계산한다
 * 저장된 규칙도 계산할 때마다 다시 검증한다 (화이트리스트가 바뀌어도 잘못된 SQL 이 나가지 않게)
 */
@Service
@RequiredArgsConstructor
public class SegmentServiceImpl implements SegmentService {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private static final int MAX_PAGE_SIZE = 100;

	private final SegmentMapper segmentMapper;
	private final ObjectMapper objectMapper;

	/** 구간 간 인터페이스 (PRD 10.1) — 팀원2 일회성 발송·워크플로우가 호출. 없는 세그먼트는 COMMON_NOT_FOUND */
	@Override
	@Transactional(readOnly = true)
	public List<Long> findTargetCustomers(long segmentId) {
		return segmentMapper.findTargetCustomerIds(query(find(segmentId)));
	}

	@Transactional
	public SegmentResponse create(SegmentRequest req, long memberId) {
		SegmentRuleTranslator.translate(req.rule(), today());
		Segment segment = Segment.create(req.name().trim(), blankToNull(req.description()), req.rule().toString(),
			memberId);
		segmentMapper.insert(segment);
		segmentMapper.insertRule(segment.getSegmentId(), segment.getRuleJson());
		return get(segment.getSegmentId());
	}

	/** 이름·설명·규칙을 통째로 바꾼다. 세그먼트는 동적이라 바뀐 규칙이 다음 계산부터 바로 쓰인다 */
	@Transactional
	public SegmentResponse update(long segmentId, SegmentRequest req) {
		SegmentRuleTranslator.translate(req.rule(), today());
		if (segmentMapper.update(segmentId, req.name().trim(), blankToNull(req.description())) == 0) {
			throw new BusinessException(CommonErrorCode.COMMON_NOT_FOUND);
		}
		segmentMapper.updateRule(segmentId, req.rule().toString());
		return get(segmentId);
	}

	/** 캠페인이 참조 중이면 SEGMENT_IN_USE (API_SPEC 4장). 규칙은 CASCADE 로 함께 지워진다 */
	@Transactional
	public void delete(long segmentId) {
		if (segmentMapper.existsCampaign(segmentId)) {
			throw new BusinessException(SegmentErrorCode.SEGMENT_IN_USE);
		}
		try {
			if (segmentMapper.delete(segmentId) == 0) {
				throw new BusinessException(CommonErrorCode.COMMON_NOT_FOUND);
			}
		} catch (DataIntegrityViolationException e) {
			// 확인과 삭제 사이에 캠페인이 이 세그먼트로 만들어진 경우 (FK 위반)
			throw new BusinessException(SegmentErrorCode.SEGMENT_IN_USE);
		}
	}

	@Transactional(readOnly = true)
	public SegmentResponse get(long segmentId) {
		return toResponse(find(segmentId));
	}

	/** 최신순. 세그먼트마다 현재 대상 수를 센다 (페이지당 최대 100번, Plan 5장) */
	@Transactional(readOnly = true)
	public PageResponse<SegmentResponse> list(int page, int size) {
		if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
			throw new BusinessException(CommonErrorCode.COMMON_INVALID_INPUT,
				"page 는 0 이상, size 는 1~" + MAX_PAGE_SIZE, null);
		}
		List<SegmentResponse> content = segmentMapper.findPage(size, (long) page * size).stream()
			.map(this::toResponse).toList();
		return PageResponse.of(content, page, size, segmentMapper.count());
	}

	@Transactional(readOnly = true)
	public SegmentPreviewResponse preview(JsonNode rule) {
		return segmentMapper.preview(SegmentRuleTranslator.translate(rule, today()));
	}

	private SegmentResponse toResponse(Segment s) {
		return new SegmentResponse(s.getSegmentId(), s.getName(), s.getDescription(), rule(s),
			segmentMapper.countTargets(query(s)), s.getCreatedBy(), s.getCreatedAt(), s.getUpdatedAt());
	}

	private Segment find(long segmentId) {
		Segment s = segmentMapper.findById(segmentId);
		if (s == null) {
			throw new BusinessException(CommonErrorCode.COMMON_NOT_FOUND);
		}
		return s;
	}

	private SegmentQuery query(Segment s) {
		return SegmentRuleTranslator.translate(rule(s), today());
	}

	private JsonNode rule(Segment s) {
		return objectMapper.readTree(s.getRuleJson());
	}

	private static LocalDate today() {
		return LocalDate.now(SEOUL);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
