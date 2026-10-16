package com.withus.coupon.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import com.withus.coupon.domain.CouponIssueRow;
import com.withus.coupon.domain.IssueStatus;

/** 쿠폰별 발급 목록 한 줄 (관리자 OWNER·MANAGER). 매장용 12자리 쿠폰 번호(code)와 고객 쿠폰 페이지 주소용 token 을 낸다 */
public record CouponIssueResponse(long issueId, String code, String token, long customerId, String customerName, Long sendLogId,
	OffsetDateTime issuedAt, OffsetDateTime usedAt, IssueStatus status) {

	public static CouponIssueResponse from(CouponIssueRow row, LocalDate today) {
		return new CouponIssueResponse(row.getIssueId(), row.getCode(), row.getToken(), row.getCustomerId(), row.getCustomerName(),
			row.getSendLogId(), row.getIssuedAt(), row.getUsedAt(), row.statusOn(today));
	}
}
