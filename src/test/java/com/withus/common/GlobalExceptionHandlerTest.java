package com.withus.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MissingServletRequestParameterException;
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

	@Test
	void 필수_파라미터_누락은_COMMON_INVALID_INPUT_400() {
		var res = new GlobalExceptionHandler()
			.handleMissingParameter(new MissingServletRequestParameterException("targetCount", "long"));

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(res.getBody().error().code()).isEqualTo("COMMON_INVALID_INPUT");
		assertThat(res.getBody().error().message()).isEqualTo("필수 요청 값이 없습니다: targetCount");
	}
}
