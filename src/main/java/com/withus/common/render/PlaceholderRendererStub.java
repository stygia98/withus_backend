package com.withus.common.render;

import java.util.Map;

import org.springframework.stereotype.Service;

/** W1 stub — 팀원2 선개발용(치환 없이 원문 반환). 실제 구현을 넣을 때 이 파일은 삭제한다(같은 인터페이스 빈 2개면 기동 실패). */
@Service
public class PlaceholderRendererStub implements PlaceholderRenderer {

	@Override
	public String render(String template, Map<String, String> values) {
		return template; // TODO 실제 구현으로 교체
	}

	@Override
	public boolean usesDefault(String template, Map<String, String> values) {
		return false; // TODO 실제 구현으로 교체
	}
}
