package com.withus.customer.dto;

import java.time.OffsetDateTime;

import com.withus.customer.domain.Purchase;

/** 구매 한 건. 쿠폰을 쓰지 않았으면 couponIssueId·couponName 은 null */
public record PurchaseResponse(long purchaseId, long amount, Long couponIssueId, String couponName,
	OffsetDateTime purchasedAt) {

	public static PurchaseResponse of(Purchase p) {
		return new PurchaseResponse(p.getPurchaseId(), p.getAmount(), p.getCouponIssueId(), p.getCouponName(),
			p.getPurchasedAt());
	}
}
