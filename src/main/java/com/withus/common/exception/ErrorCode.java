package com.withus.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 오류 코드 공통 규약. 각 도메인은 이 인터페이스를 구현한 enum 을 둔다 (예: SegmentErrorCode)
 * 코드 형식은 도메인_사유, 목록은 API_SPEC 12장
 */
public interface ErrorCode {

	HttpStatus status();

	String code();

	String message();
}
