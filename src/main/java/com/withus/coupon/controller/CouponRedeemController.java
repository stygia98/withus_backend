package com.withus.coupon.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.withus.common.response.ApiResponse;
import com.withus.coupon.dto.CouponRedeemResponse;
import com.withus.coupon.service.CouponRedeemService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 권한표 PRD 3장 '쿠폰 사용 처리(매장 바코드)': OWNER·MANAGER·STAFF (2026-10-19 매장 직원도 처리하도록 STAFF 추가).
 * 금액·누적구매액이 바뀌는 구매 등록(고객 상세)은 그대로 OWNER·MANAGER 만이다
 */
@Tag(name = "쿠폰 사용 처리 (매장)", description = "바코드 쿠폰 번호로 조회·사용 처리·취소 (API_SPEC 8장, project #51). 권한 O M S")
@RestController
@RequestMapping("/api/v1/coupon-issues/by-code/{code}")
@PreAuthorize("hasAnyRole('OWNER','MANAGER','STAFF')")
public class CouponRedeemController {

	private final CouponRedeemService couponRedeemService;

	public CouponRedeemController(CouponRedeemService couponRedeemService) {
		this.couponRedeemService = couponRedeemService;
	}

	@Operation(summary = "쿠폰 번호 조회",
		description = "상태를 바꾸지 않는다. 숫자 12자리(공백·하이픈 무시)가 아니거나 없는 번호면 COUPON_NOT_FOUND(404).")
	@GetMapping
	public ApiResponse<CouponRedeemResponse> find(@PathVariable String code) {
		return ApiResponse.ok(CouponRedeemResponse.from(couponRedeemService.find(code), couponRedeemService.today()));
	}

	@Operation(summary = "쿠폰 사용 처리",
		description = "발급 1건당 1회, 오늘이 유효기간 안일 때만. 이미 사용 COUPON_ALREADY_USED(409), 기간 밖 COUPON_NOT_USABLE(422), "
			+ "없는 번호 COUPON_NOT_FOUND(404). 성공하면 status=USED 인 쿠폰을 돌려준다. purchase 는 만들지 않는다.")
	@PostMapping("/use")
	public ApiResponse<CouponRedeemResponse> use(@PathVariable String code) {
		return ApiResponse.ok(CouponRedeemResponse.from(couponRedeemService.use(code), couponRedeemService.today()));
	}

	@Operation(summary = "쿠폰 사용 취소",
		description = "매장에서 잘못 찍은 사용 처리를 되돌린다(used_at 을 비움, 전환 집계에서도 빠짐). 사용하지 않은 쿠폰 COUPON_NOT_USED(409), "
			+ "구매 등록으로 사용한 쿠폰 COUPON_USED_BY_PURCHASE(409), 없는 번호 COUPON_NOT_FOUND(404).")
	@PostMapping("/cancel-use")
	public ApiResponse<CouponRedeemResponse> cancelUse(@PathVariable String code) {
		return ApiResponse.ok(
			CouponRedeemResponse.from(couponRedeemService.cancelUse(code), couponRedeemService.today()));
	}
}
