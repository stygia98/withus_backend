package com.withus.coupon.controller;

import java.time.LocalDate;
import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.withus.common.response.ApiResponse;
import com.withus.common.response.PageResponse;
import com.withus.coupon.domain.Coupon;
import com.withus.coupon.domain.CouponIssueRow;
import com.withus.coupon.dto.CouponIssueResponse;
import com.withus.coupon.dto.CouponRequest;
import com.withus.coupon.dto.CouponResponse;
import com.withus.coupon.dto.CustomerCouponResponse;
import com.withus.coupon.service.CouponAdminService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@Tag(name = "쿠폰", description = "쿠폰 정의·발급 현황 (API_SPEC 7장). 권한표 PRD 3장: STAFF 는 조회만")
@RestController
@RequestMapping("/api/v1")
public class CouponController {

	private final CouponAdminService couponAdminService;

	public CouponController(CouponAdminService couponAdminService) {
		this.couponAdminService = couponAdminService;
	}

	@Operation(summary = "쿠폰 목록", description = "발급 수·사용 수 포함. page 는 0부터, size 기본 20 (최대 100).")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER','STAFF')")
	@GetMapping("/coupons")
	public ApiResponse<PageResponse<CouponResponse>> list(@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "20") int size) {
		PageResponse<Coupon> result = couponAdminService.list(page, size);
		return ApiResponse.ok(PageResponse.of(result.content().stream().map(CouponResponse::from).toList(),
			result.page(), result.size(), result.totalElements()));
	}

	@Operation(summary = "쿠폰 상세", description = "없으면 COUPON_NOT_FOUND(404).")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER','STAFF')")
	@GetMapping("/coupons/{couponId}")
	public ApiResponse<CouponResponse> detail(@PathVariable long couponId) {
		return ApiResponse.ok(CouponResponse.from(couponAdminService.get(couponId)));
	}

	@Operation(summary = "쿠폰 생성",
		description = "정률은 100% 이하이고 maxDiscountAmount 필수(COUPON_RATE_CAP_REQUIRED 400). 정액의 maxDiscountAmount 는 무시한다. "
			+ "validTo < validFrom 이면 COUPON_INVALID_PERIOD(400).")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
	@PostMapping("/coupons")
	public ApiResponse<CouponResponse> create(@Valid @RequestBody CouponRequest request) {
		return ApiResponse.ok(CouponResponse.from(couponAdminService.create(request.toDraft())));
	}

	@Operation(summary = "쿠폰 수정",
		description = "발급 이력이 있으면 validTo 를 늦추는 것만 가능하고, 다른 값이 바뀌면 COUPON_ALREADY_ISSUED(409).")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
	@PutMapping("/coupons/{couponId}")
	public ApiResponse<CouponResponse> update(@PathVariable long couponId, @Valid @RequestBody CouponRequest request) {
		return ApiResponse.ok(CouponResponse.from(couponAdminService.update(couponId, request.toDraft())));
	}

	@Operation(summary = "쿠폰 발급 목록", description = "최근 발급순. status 는 USABLE·USED·EXPIRED·NOT_STARTED.")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
	@GetMapping("/coupons/{couponId}/issues")
	public ApiResponse<PageResponse<CouponIssueResponse>> issues(@PathVariable long couponId,
		@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		PageResponse<CouponIssueRow> result = couponAdminService.issues(couponId, page, size);
		LocalDate today = couponAdminService.today();
		return ApiResponse.ok(PageResponse.of(
			result.content().stream().map(row -> CouponIssueResponse.from(row, today)).toList(),
			result.page(), result.size(), result.totalElements()));
	}

	@Operation(summary = "고객별 발급 쿠폰",
		description = "구매 등록 화면용. usable=true 면 미사용이고 오늘이 유효기간 안인 쿠폰만 (팀원1 구매 등록 → CouponService.markUsed).")
	@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
	@GetMapping("/customers/{customerId}/coupon-issues")
	public ApiResponse<List<CustomerCouponResponse>> customerIssues(@PathVariable long customerId,
		@RequestParam(defaultValue = "false") boolean usable) {
		LocalDate today = couponAdminService.today();
		return ApiResponse.ok(couponAdminService.customerIssues(customerId, usable).stream()
			.map(row -> CustomerCouponResponse.from(row, today)).toList());
	}
}
