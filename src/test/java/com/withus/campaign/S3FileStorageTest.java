package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.withus.campaign.service.S3FileStorage;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Utilities;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** S3 클라이언트를 목킹해 키·접두어·Content-Type·공개 URL만 검증한다 — AWS 를 호출하지 않는다 (실제 S3 는 W5 에서 수동 확인) */
class S3FileStorageTest {

	private final S3Client s3 = mock(S3Client.class);
	private final S3FileStorage storage = new S3FileStorage(s3, "withus-test-bucket");

	@Test
	void 저장하면_images_접두어_아래에_UUID_키로_올라가고_Content_Type이_전달된다() {
		String key = storage.store("원본.PNG", new ByteArrayInputStream("데이터".getBytes()), "image/png");

		assertThat(key).endsWith(".png").doesNotContain("/");
		ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
		verify(s3).putObject(captor.capture(), any(RequestBody.class));
		assertThat(captor.getValue().bucket()).isEqualTo("withus-test-bucket");
		assertThat(captor.getValue().key()).isEqualTo("images/" + key);
		assertThat(captor.getValue().contentType()).isEqualTo("image/png");
	}

	@Test
	void 원본_파일명에_경로_조작_문자가_있어도_키에_남지_않는다() {
		String key = storage.store("../../etc/passwd", new ByteArrayInputStream("x".getBytes()), "text/plain");

		assertThat(key).doesNotContain("..", "/", "\\");
	}

	@Test
	void 공개_URL은_S3_공개_읽기_URL을_그대로_돌려준다() {
		when(s3.utilities()).thenReturn(S3Utilities.builder().region(Region.AP_NORTHEAST_2).build());

		String url = storage.publicUrl("abc.png");

		assertThat(url).isEqualTo("https://withus-test-bucket.s3.ap-northeast-2.amazonaws.com/images/abc.png");
	}

	@Test
	void 버킷이_비어_있으면_기동을_막는다() {
		assertThatThrownBy(() -> new S3FileStorage(s3, " ")).isInstanceOf(IllegalStateException.class);
	}
}
