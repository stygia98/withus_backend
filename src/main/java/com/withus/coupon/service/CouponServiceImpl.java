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

	/** 번호가 연달아 겹칠 확률은 사실상 0이지만, 끝없이 돌지 않게 한도를 둔다 */
	private static final int MAX_CODE_ATTEMPTS = 3;

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
		// 쿠폰 번호(1조 칸)가 겹치면 INSERT 가 0건이 된다 — 새 기본값으로 다시 넣는다
		for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
			couponMapper.insertIssueIfAbsent(couponId, customerId, sendLogId);
			String token = couponMapper.findTokenBySendLog(sendLogId);
			if (token != null) {
				return UUID.fromString(token);
			}
		}
		throw new IllegalStateException("쿠폰 번호를 만들지 못했습니다. sendLogId=" + sendLogId);
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

	/** 사용 처리가 0건일 때 사유를 가린다: 없음 404, 이미 사용 409, 그 외(매장 바코드 사용의 기간 밖) 422 */
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
