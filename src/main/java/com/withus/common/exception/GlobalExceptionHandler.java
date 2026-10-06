package com.withus.common.exception;

import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.withus.auth.domain.AuthErrorCode;
import com.withus.common.response.ApiResponse;

/**
 * 컨트롤러에서 발생한 예외를 공통 응답 형식으로 바꾼다
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
		return toResponse(e.getErrorCode(), e.getMessage(), e.getDetails());
	}

	/** @Valid 검증 실패: details 에 필드별 사유 */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
		Map<String, String> details = e.getBindingResult().getFieldErrors().stream()
			.collect(Collectors.toMap(FieldError::getField,
				fe -> fe.getDefaultMessage() == null ? "" : fe.getDefaultMessage(), (a, b) -> a));
		return toResponse(CommonErrorCode.COMMON_INVALID_INPUT, CommonErrorCode.COMMON_INVALID_INPUT.message(), details);
	}

	@ExceptionHandler({ HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class })
	public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception e) {
		return toResponse(CommonErrorCode.COMMON_INVALID_INPUT, CommonErrorCode.COMMON_INVALID_INPUT.message(), null);
	}

	/** 필수 @RequestParam 누락: 메시지에는 빠진 파라미터 이름만 넣는다 (사용자 입력값은 넣지 않음) */
	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<ApiResponse<Void>> handleMissingParameter(MissingServletRequestParameterException e) {
		return toResponse(CommonErrorCode.COMMON_INVALID_INPUT, "필수 요청 값이 없습니다: " + e.getParameterName(), null);
	}

	/** multipart 한도 초과는 컨트롤러 전에 나므로 서비스의 크기 검사 대신 여기서 400 으로 바꾼다 (API_SPEC 12장) */
	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public ResponseEntity<ApiResponse<Void>> handleTooLarge(MaxUploadSizeExceededException e) {
		return toResponse(CommonErrorCode.UPLOAD_FILE_TOO_LARGE, CommonErrorCode.UPLOAD_FILE_TOO_LARGE.message(), null);
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ApiResponse<Void>> handleNotFound(NoResourceFoundException e) {
		return toResponse(CommonErrorCode.COMMON_NOT_FOUND, CommonErrorCode.COMMON_NOT_FOUND.message(), null);
	}

	/** @PreAuthorize 권한 부족 */
	@ExceptionHandler(AccessDeniedException.class)
	public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e) {
		return toResponse(AuthErrorCode.AUTH_FORBIDDEN, AuthErrorCode.AUTH_FORBIDDEN.message(), null);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
		log.error("처리되지 않은 예외", e);
		return toResponse(CommonErrorCode.COMMON_INTERNAL_ERROR, CommonErrorCode.COMMON_INTERNAL_ERROR.message(), null);
	}

	private ResponseEntity<ApiResponse<Void>> toResponse(ErrorCode code, String message, Object details) {
		return ResponseEntity.status(code.status()).body(ApiResponse.fail(code, message, details));
	}
}
