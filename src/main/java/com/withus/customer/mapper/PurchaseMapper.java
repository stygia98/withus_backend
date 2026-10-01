package com.withus.customer.mapper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.withus.customer.domain.Purchase;

@Mapper
public interface PurchaseMapper {

	/**
	 * 쿠폰 발급 건의 상태 (coupon 테이블은 조회만, 쓰기는 CouponService).
	 * 이 고객 발급 건이 아니면 null, 사용됐으면 USED, on 이 유효기간 안이면 USABLE, 아니면 OUT_OF_PERIOD
	 */
	String findCouponIssueState(@Param("couponIssueId") long couponIssueId, @Param("customerId") long customerId,
		@Param("on") LocalDate on);

	/** 쿠폰 발급 건당 구매 1건 (uq_purchase_coupon_issue). 중복이면 DuplicateKeyException */
	long insert(@Param("customerId") long customerId, @Param("amount") long amount,
		@Param("couponIssueId") Long couponIssueId, @Param("createdBy") long createdBy,
		@Param("purchasedAt") OffsetDateTime purchasedAt);

	/** 누적구매액 가산. 삭제된 고객이면 0 */
	int addTotalPurchase(@Param("customerId") long customerId, @Param("amount") long amount);

	Purchase findById(long purchaseId);

	/** 최신 구매순 */
	List<Purchase> findByCustomer(long customerId);
}
