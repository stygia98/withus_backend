package com.withus.customer.service;

import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.withus.common.domain.Channel;
import com.withus.customer.mapper.CustomerMapper;

import lombok.RequiredArgsConstructor;

/** 구간 간 인터페이스 (PRD 10.1) — 팀원2 발송 큐가 적재 때와 발송 직전에 호출한다 (PRD 8.2) */
@Service
@RequiredArgsConstructor
public class ConsentServiceImpl implements ConsentService {

	private final CustomerMapper customerMapper;

	/** 없는 고객, 휴대폰 없는 고객의 SMS 도 false. 대량 호출되므로 고객당 쿼리 1회 */
	@Override
	@Transactional(readOnly = true)
	public boolean isSendable(long customerId, Channel channel) {
		return customerMapper.isSendable(customerId, Objects.requireNonNull(channel, "channel"));
	}
}
