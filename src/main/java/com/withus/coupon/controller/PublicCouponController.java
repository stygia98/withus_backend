package com.withus.coupon.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.withus.common.response.ApiResponse;
import com.withus.coupon.dto.PublicCouponResponse;
import com.withus.coupon.service.PublicCouponService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 인증 없이 접근 (SecurityConfig PUBLIC_PATHS 의 /api/v1/public/**). 토큰은 coupon_issue.token(UUID) */
@Tag(name = "고객 쿠폰 (공개)", description = "고객 쿠폰 페이지 /c/[token] 용 (API_SPEC 8장)")
@RestController
@RequestMapping("/api/v1/public/coupons/{token}")
public class PublicCouponController {

	private final PublicCouponService publicCouponService;

	public PublicCouponController(PublicCouponService publicCouponService) {
		this.publicCouponService = publicCouponService;
	}

	@Operation(summary = "쿠폰 카드 조회", description = "상태를 바꾸지 않는다. 없는 토큰은 COUPON_NOT_FOUND(404).")
	@GetMapping
	public ApiResponse<PublicCouponResponse> card(@PathVariable String token) {
		return ApiResponse.ok(PublicCouponResponse.from(publicCouponService.find(token), publicCouponService.today()));
	}

	@Operation(summary = "쿠폰 사용하기",
		description = "발급 1건당 1회, 유효기간 안에서만. 이미 사용 COUPON_ALREADY_USED(409), 기간 밖 COUPON_NOT_USABLE(422). "
			+ "성공하면 status=USED 인 카드를 돌려준다.")
	@PostMapping("/use")
	public ApiResponse<PublicCouponResponse> use(@PathVariable String token) {
		return ApiResponse.ok(PublicCouponResponse.from(publicCouponService.use(token), publicCouponService.today()));
	}
}
