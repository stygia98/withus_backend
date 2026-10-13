package com.withus.coupon.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.withus.common.exception.BusinessException;
import com.withus.coupon.domain.CouponErrorCode;
import com.withus.coupon.domain.CouponIssueRow;
import com.withus.coupon.mapper.CouponMapper;

/**
 * 고객 쿠폰 페이지 /c/[token] (API_SPEC 8장, PRD F-10). 인증 없이 추측할 수 없는 UUID 토큰으로만 접근한다.
 * 조회만 한다 — 쿠폰은 오프라인 전용이라 사용 처리는 매장 직원이 바코드로 한다(CouponRedeemService, project #51).
 */
@Service
public class PublicCouponService {

	private final CouponMapper couponMapper;
	private Clock clock = Clock.system(ZoneId.of("Asia/Seoul"));

	public PublicCouponService(CouponMapper couponMapper) {
		this.couponMapper = couponMapper;
	}

	void setClock(Clock clock) {
		this.clock = clock;
	}

	public LocalDate today() {
		return LocalDate.now(clock);
	}

	public CouponIssueRow find(String token) {
		CouponIssueRow issue = couponMapper.findIssueByToken(normalizeToken(token));
		if (issue == null) {
			throw new BusinessException(CouponErrorCode.COUPON_NOT_FOUND);
		}
		return issue;
	}

	/** UUID 형식이 아니면 DB 에서 형 변환 오류가 나기 전에 '없는 쿠폰'으로 처리한다 */
	private static String normalizeToken(String token) {
		try {
			return UUID.fromString(token).toString();
		} catch (IllegalArgumentException | NullPointerException e) {
			throw new BusinessException(CouponErrorCode.COUPON_NOT_FOUND);
		}
	}
}
