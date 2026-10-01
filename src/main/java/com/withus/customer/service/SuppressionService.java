package com.withus.customer.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.withus.common.domain.Channel;
import com.withus.customer.mapper.CustomerMapper;

import lombok.RequiredArgsConstructor;

/**
 * 수신거부 목록 추가 (PRD 7장). 수신거부 페이지·SES 반송·스팸신고가 같이 쓴다
 * - suppression 에 값 추가 (이미 있으면 그대로)
 * - 그 값을 가진 삭제되지 않은 고객의 채널 동의를 N 으로 바꾸고 consent_history 에 사유를 남긴다
 */
@Service
@RequiredArgsConstructor
public class SuppressionService {

	private final CustomerMapper customerMapper;

	/** value 는 정규화된 이메일 또는 휴대폰. reason: UNSUBSCRIBE, BOUNCE, COMPLAINT (이력 source 와 같다) */
	@Transactional
	public void suppress(Channel channel, String value, String reason) {
		customerMapper.insertSuppression(channel, value, reason);
		for (long customerId : customerMapper.rejectConsentByValue(channel, value)) {
			customerMapper.insertConsentHistory(customerId, channel, "Y", "N", reason, null);
		}
	}
}
