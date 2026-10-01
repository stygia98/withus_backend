package com.withus.tracking.service;

import org.springframework.stereotype.Service;

/** W1 stub — 팀원2 선개발용 고정값(사람 이벤트 없음). 실제 구현을 넣을 때 이 파일은 삭제한다(같은 인터페이스 빈 2개면 기동 실패). */
@Service
public class TrackEventRepositoryStub implements TrackEventRepository {

	@Override
	public boolean existsHumanEvent(long sendLogId, String eventType) {
		return false; // TODO 실제 구현으로 교체
	}
}
