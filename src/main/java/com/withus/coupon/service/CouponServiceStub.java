package com.withus.coupon.service;

import java.util.UUID;

import org.springframework.stereotype.Service;

/** W1 stub — 팀원2 선개발용(DB 저장 없이 임의 토큰 반환). 실제 구현을 넣을 때 이 파일은 삭제한다(같은 인터페이스 빈 2개면 기동 실패). */
@Service
public class CouponServiceStub implements CouponService {

	@Override
	public UUID issue(long couponId, long customerId, long sendLogId) {
		return UUID.randomUUID(); // TODO 실제 구현으로 교체
	}

	@Override
	public void markUsed(long couponIssueId) {
		// TODO 실제 구현으로 교체
	}
}
