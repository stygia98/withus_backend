package com.withus.customer.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.withus.common.response.ApiResponse;
import com.withus.customer.service.SesWebhookService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/** 인증·CSRF 없이 접근 (SecurityConfig PUBLIC_PATHS /api/webhooks/**). SNS 서명으로 검증한다 */
@Tag(name = "SES 웹훅", description = "SES 반송·스팸신고 SNS 알림 (API_SPEC 9장, PRD 8.2)")
@RestController
@RequiredArgsConstructor
public class SesWebhookController {

	private final SesWebhookService sesWebhookService;

	/** SNS 는 Content-Type text/plain 으로 JSON 을 보내므로 문자열로 받는다 */
	@Operation(summary = "SES 반송·스팸신고 수신", description = "SubscriptionConfirmation 은 구독 확인, "
		+ "영구 반송·스팸신고는 suppression 추가와 이메일 동의 N. 서명·토픽 검증 실패는 무시하고 항상 200")
	@PostMapping("/api/webhooks/ses")
	public ApiResponse<Void> receive(@RequestBody(required = false) String body) {
		sesWebhookService.handle(body == null ? "" : body);
		return ApiResponse.ok(null);
	}
}
