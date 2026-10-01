package com.withus.ai.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.withus.ai.dto.CopyDraftRequest;
import com.withus.ai.dto.CopyDraftResponse;
import com.withus.ai.service.CopyDraftService;
import com.withus.common.response.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@Tag(name = "AI", description = "AI-01 문구 초안 등 (API_SPEC 11장). Gemini 무료 등급 — 한도 초과 시 잠시 후 재시도")
@RestController
@RequestMapping("/api/v1/ai")
public class AiController {

	private final CopyDraftService copyDraftService;

	public AiController(CopyDraftService copyDraftService) {
		this.copyDraftService = copyDraftService;
	}

	@Operation(summary = "AI-01 메일 문구 초안 3안",
		description = "본문은 빈 줄로 문단을 나눈 평문, 치환자는 {{name|고객}} 만. "
			+ "오류: AI_PII_DETECTED(400, 입력에 이메일·전화번호), AI_RATE_LIMITED(429), AI_UNAVAILABLE(503).")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER','STAFF')")
	@PostMapping("/copy-drafts")
	public ApiResponse<CopyDraftResponse> copyDrafts(@Valid @RequestBody CopyDraftRequest request) {
		return ApiResponse.ok(copyDraftService.generate(request));
	}
}
