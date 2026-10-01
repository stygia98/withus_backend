package com.withus.tracking.service;

import org.springframework.stereotype.Service;

/** W1 stub — 팀원2 선개발용(본문을 그대로 돌려줌). 실제 구현을 넣을 때 이 파일은 삭제한다(같은 인터페이스 빈 2개면 기동 실패). */
@Service
public class TrackingLinkServiceStub implements TrackingLinkService {

	@Override
	public String rewrite(String html, long sendLogId) {
		return html; // TODO 실제 구현으로 교체
	}
}
