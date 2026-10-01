package com.withus.customer.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.withus.common.response.ApiResponse;
import com.withus.customer.dto.UnsubscribeInfoResponse;
import com.withus.customer.dto.UnsubscribeRequest;
import com.withus.customer.dto.UnsubscribeResponse;
import com.withus.customer.service.UnsubscribeService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 인증·CSRF 없이 접근 (SecurityConfig PUBLIC_PATHS). 토큰은 UnsubscribeTokens(HMAC) 로 검증한다 */
@Tag(name = "수신거부 (공개)", description = "수신거부 페이지 /unsubscribe/[token]·메일 헤더 원클릭 (API_SPEC 8장, PRD 8.3)")
@RestController
@RequiredArgsConstructor
public class UnsubscribeController {

	private final UnsubscribeService unsubscribeService;

	@Operation(summary = "수신거부 확인 화면 정보", description = "상태를 바꾸지 않는다(링크 스캐너 대응). "
		+ "토큰 검증 실패는 UNSUBSCRIBE_INVALID_TOKEN(400)")
	@GetMapping("/api/v1/public/unsubscribe/{token}")
	public ApiResponse<UnsubscribeInfoResponse> info(@PathVariable String token) {
		return ApiResponse.ok(unsubscribeService.info(token));
	}

	@Operation(summary = "수신거부 처리", description = "고른 채널의 동의 N, suppression 추가, consent_history(UNSUBSCRIBE). "
		+ "다시 보내도 결과는 같다")
	@PostMapping("/api/v1/public/unsubscribe/{token}")
	public ApiResponse<UnsubscribeResponse> unsubscribe(@PathVariable String token,
		@Valid @RequestBody UnsubscribeRequest request) {
		return ApiResponse.ok(unsubscribeService.unsubscribe(token, request.channel()));
	}

	@Operation(summary = "원클릭 수신거부", description = "메일 헤더 List-Unsubscribe-Post 용 (RFC 8058). 이메일 채널만. "
		+ "본문(List-Unsubscribe=One-Click)은 보지 않는다")
	@PostMapping("/api/v1/unsubscribe/one-click/{token}")
	public ApiResponse<UnsubscribeResponse> oneClick(@PathVariable String token) {
		return ApiResponse.ok(unsubscribeService.unsubscribe(token, UnsubscribeRequest.Target.EMAIL));
	}
}
