package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.withus.campaign.service.LocalFileStorage;

/** DB 없이 실행 가능한 순수 단위 테스트 */
class LocalFileStorageTest {

	@TempDir
	Path tempDir;

	@Test
	void 저장하면_local_path_아래에_UUID_파일명으로_생긴다() {
		LocalFileStorage storage = new LocalFileStorage(tempDir.toString());

		String key = storage.store("원본.jpg", new ByteArrayInputStream("데이터".getBytes()), "image/jpeg");

		assertThat(key).endsWith(".jpg");
		Path saved = tempDir.resolve(key);
		assertThat(saved).exists();
		assertThat(saved.getParent()).isEqualTo(tempDir.toAbsolutePath().normalize());
	}

	@Test
	void 원본_파일명에_경로_조작_문자가_있어도_local_path_밖에_저장되지_않는다() throws Exception {
		LocalFileStorage storage = new LocalFileStorage(tempDir.toString());

		String key = storage.store("../../../../etc/passwd", new ByteArrayInputStream("x".getBytes()), "text/plain");

		assertThat(key).doesNotContain("..", "/", "\\");
		assertThat(tempDir.resolve(key)).exists();
		// local-path 밖(상위 디렉터리)에는 아무것도 생기지 않았다
		try (var siblingFiles = Files.list(tempDir.getParent())) {
			assertThat(siblingFiles).noneMatch(p -> p.getFileName().toString().equals("passwd"));
		}
	}

	@Test
	void 확장자가_없는_원본_파일명도_저장된다() {
		LocalFileStorage storage = new LocalFileStorage(tempDir.toString());

		String key = storage.store("확장자없음", new ByteArrayInputStream("x".getBytes()), "application/octet-stream");

		assertThat(tempDir.resolve(key)).exists();
	}
}
