package com.withus.campaign.service;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

/**
 * 파일 저장소 — local(디스크, W1)·s3(운영)를 프로필로 전환한다 (CLAUDE.md 4장: 외부 연동은 인터페이스 뒤에)
 * 이번에는 campaign 패키지에서 템플릿 이미지 업로드만 다룬다 (PL 확정, 이슈 #78). CSV 원본 저장 공용화는 필요가 생기면 PL이 정리한다
 */
public interface FileStorage {

	/**
	 * 파일을 저장하고 저장소 안에서 쓰이는 키(파일명)를 돌려준다.
	 * 원본 파일명은 신뢰하지 않는다 — 확장자만 참고해 구현체가 새 이름(UUID 기반)을 만든다.
	 */
	String store(String originalFilename, InputStream in, String contentType);

	/** store 가 돌려준 키로 GET 가능한 공개 URL을 만든다 */
	String publicUrl(String key);

	/** UUID 기반 새 키. 디렉터리 성분(../, /)은 getFileName() 으로 제거하고 확장자만 남긴다 */
	static String newKey(String originalFilename) {
		String extension = "";
		if (originalFilename != null && !originalFilename.isBlank()) {
			String name = Path.of(originalFilename).getFileName().toString();
			int dot = name.lastIndexOf('.');
			extension = dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
		}
		return UUID.randomUUID() + extension;
	}
}
