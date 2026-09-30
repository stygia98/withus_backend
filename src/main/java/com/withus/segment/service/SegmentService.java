package com.withus.segment.service;

import java.util.List;

/**
 * 구간 간 연결 인터페이스 (PRD 10.1) — 제공: 팀원1(고객), 호출: 팀원2(발송·워크플로우)
 * 시그니처 확정. 변경은 PL 리뷰로만 한다.
 */
public interface SegmentService {

	/** 세그먼트 규칙에 맞는 삭제되지 않은 고객 ID 목록 */
	List<Long> findTargetCustomers(long segmentId);
}
