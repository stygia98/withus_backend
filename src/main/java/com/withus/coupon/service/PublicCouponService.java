package com.withus.coupon.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.withus.common.exception.BusinessException;
import com.withus.coupon.domain.CouponErrorCode;
import com.withus.coupon.domain.CouponIssueRow;
import com.withus.coupon.mapper.CouponMapper;

/**
 * 고객 쿠폰 페이지 /c/[token] (API_SPEC 8장, PRD F-10). 인증 없이 추측할 수 없는 UUID 토큰으로만 접근한다.
 * 조회(GET)는 절대 상태를 바꾸지 않고, 사용 처리는 POST 로만 한다 — 메일 링크 스캐너가 GET 을 먼저 열어 보기 때문이다.
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

	/** 발급 1건당 1회, 유효기간 안에서만. 금액이 없으므로 purchase 는 만들지 않는다 (PRD F-10 ②) */
	@Transactional
	public CouponIssueRow use(String token) {
		String normalized = normalizeToken(token);
		if (couponMapper.markUsedByToken(normalized, today()) == 0) {
			throw CouponServiceImpl.failureOf(couponMapper.findIssueByToken(normalized));
		}
		return couponMapper.findIssueByToken(normalized);
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
