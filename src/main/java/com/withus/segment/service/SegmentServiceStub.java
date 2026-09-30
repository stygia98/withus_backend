package com.withus.segment.service;

import java.util.List;

import org.springframework.stereotype.Service;

/** W1 stub — 팀원2 선개발용 고정값. 실제 구현을 넣을 때 이 파일은 삭제한다(같은 인터페이스 빈 2개면 기동 실패). */
@Service
public class SegmentServiceStub implements SegmentService {

	@Override
	public List<Long> findTargetCustomers(long segmentId) {
		return List.of(); // TODO 실제 구현으로 교체
	}
}
