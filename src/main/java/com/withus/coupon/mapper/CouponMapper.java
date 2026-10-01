package com.withus.coupon.mapper;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.withus.coupon.domain.Coupon;
import com.withus.coupon.domain.CouponDraft;
import com.withus.coupon.domain.CouponIssueRow;

@Mapper
public interface CouponMapper {

	// ---- 쿠폰 정의 ----

	List<Coupon> findList(@Param("offset") int offset, @Param("limit") int limit);

	long count();

	Coupon findById(@Param("couponId") long couponId);

	/** 수정 중 발급이 끼어들지 않도록 행을 잠근다. 없으면 null */
	Long lockById(@Param("couponId") long couponId);

	long countIssues(@Param("couponId") long couponId);

	/** 새 쿠폰 ID 를 돌려준다 */
	long insert(@Param("d") CouponDraft draft);

	void update(@Param("couponId") long couponId, @Param("d") CouponDraft draft);

	// ---- 발급 ----

	/** 같은 send_log_id 가 이미 있으면 아무것도 하지 않는다 (발송 1건당 발급 1건) */
	void insertIssueIfAbsent(@Param("couponId") long couponId, @Param("customerId") long customerId,
		@Param("sendLogId") long sendLogId);

	String findTokenBySendLog(@Param("sendLogId") long sendLogId);

	/** 미사용이고 today 가 유효기간 안일 때만 사용 처리한다. 바뀐 행 수(0 또는 1) */
	int markUsedById(@Param("issueId") long issueId, @Param("today") LocalDate today);

	int markUsedByToken(@Param("token") String token, @Param("today") LocalDate today);

	CouponIssueRow findIssueById(@Param("issueId") long issueId);

	CouponIssueRow findIssueByToken(@Param("token") String token);

	List<CouponIssueRow> findIssuesByCoupon(@Param("couponId") long couponId, @Param("offset") int offset,
		@Param("limit") int limit);

	long countIssuesByCoupon(@Param("couponId") long couponId);

	/** usableOn 이 있으면 그날 사용 가능한(미사용·기간 안) 발급만 */
	List<CouponIssueRow> findIssuesByCustomer(@Param("customerId") long customerId,
		@Param("usableOn") LocalDate usableOn);
}
