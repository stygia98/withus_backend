package com.withus.customer.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.withus.common.domain.Channel;

/**
 * 구간 간 연결 인터페이스 (PRD 10.1 "수신동의 확인") — 제공: 팀원1, 호출: 팀원2(적재·발송 직전 재확인)
 * 시그니처 확정. 변경은 PL 리뷰로만 한다.
 */
public interface ConsentService {

	/** 수신동의 Y, suppression 미포함, 삭제되지 않은 고객이면 true (PRD 8.2) */
	boolean isSendable(long customerId, Channel channel);

	/** isSendable 과 같은 규칙을 여러 고객에 한 번에 적용한 결과(발송 가능한 ID). 적재 N+1 방지용 (#21 PL 결정 C) */
	Set<Long> filterSendable(List<Long> customerIds, Channel channel);

	/**
	 * 그 채널의 수신동의 일시 — F-12 수신동의 확인 안내(NOTICE) 본문용 (backend #81 PL 결정).
	 * 삭제된 고객, 채널 동의 N, 없는 고객이면 empty
	 */
	Optional<OffsetDateTime> findConsentAt(long customerId, Channel channel);
}
