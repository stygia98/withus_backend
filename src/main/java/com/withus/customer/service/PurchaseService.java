package com.withus.customer.service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.withus.common.exception.BusinessException;
import com.withus.common.exception.CommonErrorCode;
import com.withus.coupon.service.CouponService;
import com.withus.customer.domain.PurchaseErrorCode;
import com.withus.customer.dto.PurchaseCreateRequest;
import com.withus.customer.dto.PurchaseResponse;
import com.withus.customer.mapper.CustomerMapper;
import com.withus.customer.mapper.PurchaseMapper;

import lombok.RequiredArgsConstructor;

/**
 * 구매 등록 (PRD F-10 ①, API_SPEC 3장). 관리자 화면에서만 등록한다
 * - purchase 저장, 누적구매액 가산, 쿠폰을 고르면 CouponService.markUsed 로 사용 처리 (쓰기는 coupon 도메인)
 * - 쿠폰 유효기간은 구매일 기준으로 본다
 */
@Service
@RequiredArgsConstructor
public class PurchaseService {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private final PurchaseMapper purchaseMapper;
	private final CustomerMapper customerMapper;
	private final CouponService couponService;

	@Transactional
	public PurchaseResponse create(long customerId, PurchaseCreateRequest request, long memberId) {
		requireActive(customerId);
		OffsetDateTime purchasedAt = request.purchasedAt() != null ? request.purchasedAt() : OffsetDateTime.now(SEOUL);
		Long couponIssueId = request.couponIssueId();
		if (couponIssueId != null) {
			String state = purchaseMapper.findCouponIssueState(couponIssueId, customerId,
				purchasedAt.atZoneSameInstant(SEOUL).toLocalDate());
			if ("USED".equals(state)) {
				throw new BusinessException(PurchaseErrorCode.COUPON_ALREADY_USED);
			}
			if (!"USABLE".equals(state)) {
				throw new BusinessException(PurchaseErrorCode.COUPON_NOT_USABLE);
			}
		}
		long purchaseId;
		try {
			purchaseId = purchaseMapper.insert(customerId, request.amount(), couponIssueId, memberId, purchasedAt);
		} catch (DuplicateKeyException e) {
			// 확인과 저장 사이에 같은 쿠폰으로 다른 구매가 먼저 들어온 경우
			throw new BusinessException(PurchaseErrorCode.COUPON_ALREADY_USED);
		}
		purchaseMapper.addTotalPurchase(customerId, request.amount());
		if (couponIssueId != null) {
			couponService.markUsed(couponIssueId);
		}
		return PurchaseResponse.of(purchaseMapper.findById(purchaseId));
	}

	@Transactional(readOnly = true)
	public List<PurchaseResponse> list(long customerId) {
		requireActive(customerId);
		return purchaseMapper.findByCustomer(customerId).stream().map(PurchaseResponse::of).toList();
	}

	private void requireActive(long customerId) {
		if (customerMapper.findActiveById(customerId) == null) {
			throw new BusinessException(CommonErrorCode.COMMON_NOT_FOUND);
		}
	}
}
