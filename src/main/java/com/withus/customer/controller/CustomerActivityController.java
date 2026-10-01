package com.withus.customer.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.withus.common.response.ApiResponse;
import com.withus.customer.dto.CustomerActivityResponse;
import com.withus.customer.service.CustomerActivityService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "고객", description = "고객 개별 관리 (API_SPEC 3장)")
@RestController
@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
@RequiredArgsConstructor
public class CustomerActivityController {

	private final CustomerActivityService activityService;

	@Operation(summary = "발송·이벤트·쿠폰 이력", description = "sends: 최근 발송 100건(최신순), 오픈·클릭은 봇 제외 첫 이벤트 시각. "
		+ "coupons: 발급 쿠폰 전체(최신 발급순), status 는 오늘 기준 USABLE·USED·EXPIRED·NOT_STARTED. "
		+ "삭제된 고객은 COMMON_NOT_FOUND(404)")
	@GetMapping("/api/v1/customers/{customerId}/activity")
	public ApiResponse<CustomerActivityResponse> get(@PathVariable long customerId) {
		return ApiResponse.ok(activityService.get(customerId));
	}
}
