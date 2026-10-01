package com.withus.campaign.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** withus.storage.type=local 일 때만 활성화. withus.storage.local-path 아래에 저장한다 */
@Component
@ConditionalOnProperty(prefix = "withus.storage", name = "type", havingValue = "local")
public class LocalFileStorage implements FileStorage {

	private final Path root;

	public LocalFileStorage(@Value("${withus.storage.local-path}") String localPath) {
		this.root = Path.of(localPath).toAbsolutePath().normalize();
		try {
			Files.createDirectories(root);
		} catch (IOException e) {
			throw new UncheckedIOException("업로드 디렉터리를 만들 수 없습니다: " + root, e);
		}
	}

	@Override
	public String store(String originalFilename, InputStream in, String contentType) {
		String key = UUID.randomUUID() + extensionOf(originalFilename);
		Path target = root.resolve(key).normalize();
		if (!target.getParent().equals(root)) {
			// UUID 로 새로 만든 이름이라 사실상 발생하지 않지만, 경로 조작에 대한 방어선을 하나 더 둔다
			throw new IllegalArgumentException("허용되지 않은 저장 경로입니다.");
		}
		try (in) {
			Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			throw new UncheckedIOException("파일 저장에 실패했습니다: " + key, e);
		}
		return key;
	}

	/** 디렉터리 성분(../, /)은 getFileName() 으로 제거하고 확장자만 남긴다 */
	private String extensionOf(String originalFilename) {
		if (originalFilename == null || originalFilename.isBlank()) {
			return "";
		}
		String name = Path.of(originalFilename).getFileName().toString();
		int dot = name.lastIndexOf('.');
		return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
	}
}
