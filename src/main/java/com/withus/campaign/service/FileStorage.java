package com.withus.campaign.service;

import java.io.InputStream;

/**
 * 파일 저장소 — local(디스크, W1)·s3(운영, W4)를 프로필로 전환한다 (CLAUDE.md 4장: 외부 연동은 인터페이스 뒤에)
 * PL 확인 전: 팀원1 CSV 원본 저장과 공용 여부가 미정이라 우선 campaign 패키지에 둔다
 */
public interface FileStorage {

	/**
	 * 파일을 저장하고 저장소 안에서 쓰이는 키(파일명)를 돌려준다.
	 * 원본 파일명은 신뢰하지 않는다 — 확장자만 참고해 구현체가 새 이름(UUID 기반)을 만든다.
	 */
	String store(String originalFilename, InputStream in, String contentType);
}
