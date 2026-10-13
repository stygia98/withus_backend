package com.withus.coupon.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.withus.common.exception.BusinessException;
import com.withus.coupon.domain.CouponErrorCode;
import com.withus.coupon.domain.CouponIssueRow;
import com.withus.coupon.mapper.CouponMapper;

/**
 * 매장 바코드 쿠폰 사용 처리 (PRD F-10 ②, API_SPEC 8장, project #51).
 * 직원이 관리자 화면 입력칸에 바코드 스캐너로 쿠폰 번호를 찍으면(스캐너는 키보드처럼 숫자를 입력한다) 조회 → 사용 처리한다.
 * 금액이 없는 경로라 purchase 는 만들지 않고 coupon_issue.used_at 만 기록한다.
 */
@Service
public class CouponRedeemService {

	/** 쿠폰 번호는 숫자 12자리. 직원이 손으로 칠 때 넣은 공백·하이픈은 지운다 */
	private static final Pattern CODE = Pattern.compile("\\d{12}");

	private final CouponMapper couponMapper;
	private Clock clock = Clock.system(ZoneId.of("Asia/Seoul"));

	public CouponRedeemService(CouponMapper couponMapper) {
		this.couponMapper = couponMapper;
	}

	void setClock(Clock clock) {
		this.clock = clock;
	}

	public LocalDate today() {
		return LocalDate.now(clock);
	}

	/** 사용 처리 전 확인용 조회 — 상태를 바꾸지 않는다 */
	public CouponIssueRow find(String code) {
		CouponIssueRow issue = couponMapper.findIssueByCode(normalize(code));
		if (issue == null) {
			throw new BusinessException(CouponErrorCode.COUPON_NOT_FOUND);
		}
		return issue;
	}

	/** 발급 1건당 1회, 오늘이 유효기간 안일 때만. 같은 번호를 두 번 찍어도 한 번만 성공한다(조건을 UPDATE 한 문장에 둠) */
	@Transactional
	public CouponIssueRow use(String code) {
		String normalized = normalize(code);
		if (couponMapper.markUsedByCode(normalized, today()) == 0) {
			throw CouponServiceImpl.failureOf(couponMapper.findIssueByCode(normalized));
		}
		return couponMapper.findIssueByCode(normalized);
	}

	/** 형식이 틀린 번호는 DB 조회 없이 '없는 쿠폰'으로 처리한다 */
	static String normalize(String code) {
		String digits = code == null ? "" : code.replaceAll("[\\s-]", "");
		if (!CODE.matcher(digits).matches()) {
			throw new BusinessException(CouponErrorCode.COUPON_NOT_FOUND);
		}
		return digits;
	}
}
