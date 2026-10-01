package com.withus.coupon.service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Service;

/**
 * W1 stub — 팀원2 선개발용(DB 저장 없이 메모리에만 기억). 실제 구현을 넣을 때 이 파일은 삭제한다(같은 인터페이스 빈 2개면 기동 실패).
 * issue 의 멱등 계약(같은 sendLogId 는 같은 토큰)은 재시도 흐름을 개발할 수 있도록 stub 도 지킨다.
 */
@Service
public class CouponServiceStub implements CouponService {

	/** sendLogId → 발급 토큰. 재시작하면 사라지는 stub 용 저장소 */
	private final ConcurrentMap<Long, UUID> issuedBySendLog = new ConcurrentHashMap<>();

	@Override
	public UUID issue(long couponId, long customerId, long sendLogId) {
		return issuedBySendLog.computeIfAbsent(sendLogId, id -> UUID.randomUUID()); // TODO 실제 구현으로 교체
	}

	@Override
	public void markUsed(long couponIssueId) {
		// TODO 실제 구현으로 교체
	}
}
