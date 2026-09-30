package com.withus.tracking.service;

/**
 * 구간 간 연결 인터페이스 (PRD 10.1) — 제공: 팀원3(전환), 호출: 팀원2(발송 직전 렌더링)
 * 시그니처 확정. 변경은 PL 리뷰로만 한다.
 */
public interface TrackingLinkService {

	/**
	 * 본문 링크를 tracking_token 기반 추적 URL로 바꾸고 오픈 픽셀을 넣는다.
	 * 수신거부·쿠폰·mailto·tel 링크는 바꾸지 않는다 (PRD 8.1)
	 */
	String rewrite(String html, long sendLogId);
}
