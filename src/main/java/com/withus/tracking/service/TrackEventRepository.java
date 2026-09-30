package com.withus.tracking.service;

/**
 * 구간 간 연결 인터페이스 (PRD 10.1) — 제공: 팀원3, 호출: 팀원2(워크플로우 CONDITION), 팀원1(휴면 판정)
 * 시그니처 변경은 W1 합의 후 PL 리뷰로만 한다.
 */
public interface TrackEventRepository {

	/** 해당 발송 건에 봇이 아닌(bot_yn = 'N') 이벤트가 있는가. eventType: OPEN / CLICK */
	boolean existsHumanEvent(long sendLogId, String eventType);
}
