package com.withus.customer.service;

import com.withus.common.domain.Channel;

/**
 * 구간 간 연결 인터페이스 (PRD 10.1 "수신동의 확인") — 제공: 팀원1, 호출: 팀원2(적재·발송 직전 재확인)
 * 시그니처 변경은 W1 합의 후 PL 리뷰로만 한다.
 */
public interface ConsentService {

	/** 수신동의 Y, suppression 미포함, 삭제되지 않은 고객이면 true (PRD 8.2) */
	boolean isSendable(long customerId, Channel channel);
}
