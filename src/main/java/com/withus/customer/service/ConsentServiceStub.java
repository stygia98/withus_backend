package com.withus.customer.service;

import org.springframework.stereotype.Service;

import com.withus.common.domain.Channel;

/** W1 stub — 팀원2 선개발용 고정값(항상 발송 가능). 실제 구현을 넣을 때 이 파일은 삭제한다(같은 인터페이스 빈 2개면 기동 실패). */
@Service
public class ConsentServiceStub implements ConsentService {

	@Override
	public boolean isSendable(long customerId, Channel channel) {
		return true; // TODO 실제 구현으로 교체
	}
}
