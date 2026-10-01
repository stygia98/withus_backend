package com.withus.customer.controller;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.withus.auth.security.AuthMember;
import com.withus.common.response.ApiResponse;
import com.withus.customer.dto.PurchaseCreateRequest;
import com.withus.customer.dto.PurchaseResponse;
import com.withus.customer.service.PurchaseService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "고객 구매", description = "구매 등록·목록 (API_SPEC 3장, PRD F-10)")
@RestController
@RequestMapping("/api/v1/customers/{customerId}/purchases")
@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
@RequiredArgsConstructor
public class PurchaseController {

	private final PurchaseService purchaseService;

	@Operation(summary = "구매 목록", description = "최신 구매순. 삭제된 고객은 COMMON_NOT_FOUND(404)")
	@GetMapping
	public ApiResponse<List<PurchaseResponse>> list(@PathVariable long customerId) {
		return ApiResponse.ok(purchaseService.list(customerId));
	}

	@Operation(summary = "구매 등록", description = "누적구매액에 더한다. couponIssueId 를 주면 사용 처리한다 — "
		+ "이 고객 발급 건이 아니거나 구매일이 유효기간 밖이면 COUPON_NOT_USABLE(422), 이미 사용됐으면 COUPON_ALREADY_USED(409)")
	@PostMapping
	public ApiResponse<PurchaseResponse> create(@PathVariable long customerId,
		@Valid @RequestBody PurchaseCreateRequest request, @AuthenticationPrincipal AuthMember me) {
		return ApiResponse.ok(purchaseService.create(customerId, request, me.memberId()));
	}
}
