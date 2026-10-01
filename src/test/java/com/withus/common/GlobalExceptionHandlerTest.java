package com.withus.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.withus.common.exception.GlobalExceptionHandler;

/** MockMvc 는 multipart 해석을 건너뛰어 한도 초과를 재현할 수 없으므로 매핑만 확인한다 */
class GlobalExceptionHandlerTest {

	@Test
	void multipart_한도_초과는_UPLOAD_FILE_TOO_LARGE_400() {
		var res = new GlobalExceptionHandler().handleTooLarge(new MaxUploadSizeExceededException(10L * 1024 * 1024));

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(res.getBody().error().code()).isEqualTo("UPLOAD_FILE_TOO_LARGE");
	}
}
