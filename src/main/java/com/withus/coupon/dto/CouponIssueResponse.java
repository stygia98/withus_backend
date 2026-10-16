package com.withus.coupon.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import com.withus.coupon.domain.CouponIssueRow;
import com.withus.coupon.domain.IssueStatus;

/** 쿠폰별 발급 목록 한 줄 (관리자). 토큰은 고객 페이지 접근 수단이라 응답에 넣지 않고, 매장용 12자리 쿠폰 번호(code)만 낸다 */
public record CouponIssueResponse(long issueId, String code, long customerId, String customerName, Long sendLogId,
	OffsetDateTime issuedAt, OffsetDateTime usedAt, IssueStatus status) {

	public static CouponIssueResponse from(CouponIssueRow row, LocalDate today) {
		return new CouponIssueResponse(row.getIssueId(), row.getCode(), row.getCustomerId(), row.getCustomerName(),
			row.getSendLogId(), row.getIssuedAt(), row.getUsedAt(), row.statusOn(today));
	}
}
