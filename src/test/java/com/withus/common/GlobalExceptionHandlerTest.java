package com.withus.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingRequestCookieException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import com.withus.common.exception.GlobalExceptionHandler;

/** MockMvc 는 multipart 해석을 건너뛰어 한도 초과를 재현할 수 없으므로 매핑만 확인한다 */
class GlobalExceptionHandlerTest {

	/** Spring 이 실제로 고르는 처리기 (예외 계층이 가장 가까운 @ExceptionHandler) */
	private static final ExceptionHandlerMethodResolver RESOLVER = new ExceptionHandlerMethodResolver(
		GlobalExceptionHandler.class);

	private static String handlerOf(Exception e) {
		Method method = RESOLVER.resolveMethod(e);
		return method == null ? null : method.getName();
	}

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

	@Test
	void 필수_파트_누락은_파트_이름만_넣은_COMMON_INVALID_INPUT_400() {
		var res = new GlobalExceptionHandler().handleMissingPart(new MissingServletRequestPartException("file"));

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(res.getBody().error().code()).isEqualTo("COMMON_INVALID_INPUT");
		assertThat(res.getBody().error().message()).isEqualTo("필수 요청 값이 없습니다: file");
	}

	@Test
	void 필수_헤더_쿠키_누락은_COMMON_INVALID_INPUT_400() {
		// 지금은 @RequestHeader·@CookieValue 를 쓰는 API 가 없지만, 생기면 500 으로 떨어지지 않게 한다 (backend #64 리뷰 후속)
		var handler = new GlobalExceptionHandler();
		var header = handler.handleBindingFailure(new MissingRequestHeaderException("X-Request-Id", null));
		var cookie = handler.handleBindingFailure(new MissingRequestCookieException("SESSION", null));

		assertThat(header.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(cookie.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(header.getBody().error().message()).isEqualTo("요청 값이 올바르지 않습니다.");
	}

	@Test
	void 하위_예외는_각자의_처리기가_잡는다() {
		assertThat(handlerOf(new MissingServletRequestParameterException("targetCount", "long")))
			.isEqualTo("handleMissingParameter");
		assertThat(handlerOf(new MissingServletRequestPartException("file"))).isEqualTo("handleMissingPart");
		assertThat(handlerOf(new MaxUploadSizeExceededException(1L))).isEqualTo("handleTooLarge");
		assertThat(handlerOf(new MissingRequestHeaderException("X-Request-Id", null))).isEqualTo("handleBindingFailure");
		assertThat(handlerOf(new MissingRequestCookieException("SESSION", null))).isEqualTo("handleBindingFailure");
		assertThat(handlerOf(new MultipartException("Current request is not a multipart request")))
			.isEqualTo("handleMultipart");
		assertThat(handlerOf(new MissingPathVariableException("campaignId", pathParameter())))
			.isEqualTo("handleMissingPathVariable");
	}

	@Test
	void 경로_변수_누락은_서버_매핑_오류라_500_변환_후_null_은_400() {
		// 지금 컨트롤러에서는 생기지 않는다. 바인딩 오류를 넓게 400 으로 잡으면서 서버 원인을 가리지 않게 한다 (backend #66 리뷰 후속)
		var handler = new GlobalExceptionHandler();

		var notMapped = handler.handleMissingPathVariable(new MissingPathVariableException("campaignId", pathParameter()));
		var convertedToNull = handler
			.handleMissingPathVariable(new MissingPathVariableException("campaignId", pathParameter(), true));

		assertThat(notMapped.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(notMapped.getBody().error().code()).isEqualTo("COMMON_INTERNAL_ERROR");
		assertThat(convertedToNull.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void multipart_오류는_원인이_IOException_이면_500_아니면_400() {
		var handler = new GlobalExceptionHandler();

		var notMultipart = handler.handleMultipart(new MultipartException("Current request is not a multipart request"));
		var storageFailure = handler.handleMultipart(
			new MultipartException("Failed to parse multipart servlet request", new IOException("임시 파일 쓰기 실패")));

		assertThat(notMultipart.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(storageFailure.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(storageFailure.getBody().error().code()).isEqualTo("COMMON_INTERNAL_ERROR");
	}

	/** MissingPathVariableException 의 메시지(500 로그)가 파라미터 타입을 읽으므로 실제 메서드 파라미터를 쓴다 */
	private static MethodParameter pathParameter() {
		try {
			return new MethodParameter(GlobalExceptionHandlerTest.class.getDeclaredMethod("sample", long.class), 0);
		}
		catch (NoSuchMethodException e) {
			throw new IllegalStateException(e);
		}
	}

	@SuppressWarnings("unused")
	private void sample(long campaignId) {
	}
}
