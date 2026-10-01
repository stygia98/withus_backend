package com.withus.coupon.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.withus.common.exception.BusinessException;
import com.withus.coupon.domain.CouponErrorCode;
import com.withus.coupon.domain.CouponIssueRow;
import com.withus.coupon.mapper.CouponMapper;

/**
 * 구간 간 연결 인터페이스 구현 — 발송 직전 발급(팀원2), 구매 등록 시 사용 처리(팀원1)
 */
@Service
public class CouponServiceImpl implements CouponService {

	private final CouponMapper couponMapper;

	public CouponServiceImpl(CouponMapper couponMapper) {
		this.couponMapper = couponMapper;
	}

	/**
	 * 유효기간은 여기서 검사하지 않는다. 기간 밖이면 발송 큐가 발급 전에 SKIPPED(COUPON_INVALID)로 처리한다 (PRD 8.2-3).
	 * 같은 sendLogId 로 다른 couponId·customerId 가 오더라도 처음 발급을 그대로 돌려준다 (발송 1건당 발급 1건).
	 */
	@Override
	@Transactional
	public UUID issue(long couponId, long customerId, long sendLogId) {
		couponMapper.insertIssueIfAbsent(couponId, customerId, sendLogId);
		return UUID.fromString(couponMapper.findTokenBySendLog(sendLogId));
	}

	/** 미사용인지만 확인한다. 유효기간(구매일 기준)과 고객 일치는 호출하는 쪽(구매 등록)이 판정한다 */
	@Override
	@Transactional
	public void markUsed(long couponIssueId) {
		if (couponMapper.markUsedById(couponIssueId) == 1) {
			return;
		}
		throw failureOf(couponMapper.findIssueById(couponIssueId));
	}

	/** 사용 처리가 0건일 때 사유를 가린다: 없음 404, 이미 사용 409, 그 외(고객 사용하기의 기간 밖) 422 */
	static BusinessException failureOf(CouponIssueRow issue) {
		if (issue == null) {
			return new BusinessException(CouponErrorCode.COUPON_NOT_FOUND);
		}
		if (issue.getUsedAt() != null) {
			return new BusinessException(CouponErrorCode.COUPON_ALREADY_USED);
		}
		return new BusinessException(CouponErrorCode.COUPON_NOT_USABLE);
	}
}
