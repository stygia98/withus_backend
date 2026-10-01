package com.withus.customer.service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.withus.common.domain.Channel;
import com.withus.common.exception.BusinessException;
import com.withus.common.privacy.Masking;
import com.withus.common.token.UnsubscribeTokens;
import com.withus.customer.domain.Customer;
import com.withus.customer.domain.CustomerErrorCode;
import com.withus.customer.dto.UnsubscribeInfoResponse;
import com.withus.customer.dto.UnsubscribeRequest.Target;
import com.withus.customer.dto.UnsubscribeResponse;
import com.withus.customer.mapper.CustomerMapper;

import lombok.RequiredArgsConstructor;

/**
 * 수신거부 (PRD F-08, 8.3). 확인 화면 조회는 상태를 바꾸지 않고, 처리는 POST 로만 한다
 * - 토큰이 틀리거나 고객이 없으면 사유 구분 없이 UNSUBSCRIBE_INVALID_TOKEN
 * - 삭제된 고객의 링크도 처리한다: suppression 은 고객 삭제와 관계없이 값으로 남는다 (7장)
 */
@Service
@RequiredArgsConstructor
public class UnsubscribeService {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private final UnsubscribeTokens unsubscribeTokens;
	private final CustomerMapper customerMapper;
	private final SuppressionService suppressionService;

	@Transactional(readOnly = true)
	public UnsubscribeInfoResponse info(String token) {
		Customer c = find(token);
		List<Channel> suppressed = customerMapper.findSuppressedChannels(c.getEmail(), c.getPhone());
		List<Channel> channels = new ArrayList<>();
		List<Channel> unsubscribed = new ArrayList<>();
		for (Channel channel : Target.ALL.channels()) {
			if (value(c, channel) == null) {
				continue;
			}
			channels.add(channel);
			String consent = channel == Channel.EMAIL ? c.getEmailConsentYn() : c.getSmsConsentYn();
			if ("N".equals(consent) || suppressed.contains(channel)) {
				unsubscribed.add(channel);
			}
		}
		// 마스킹 규칙은 고객 공개 페이지 공통 (API_SPEC 8장)
		return new UnsubscribeInfoResponse(Masking.maskName(c.getName()), channels, unsubscribed);
	}

	/** 같은 요청을 다시 보내도 결과는 같다 (이력은 동의가 실제로 바뀔 때만) */
	@Transactional
	public UnsubscribeResponse unsubscribe(String token, Target target) {
		Customer c = find(token);
		List<Channel> done = new ArrayList<>();
		for (Channel channel : target.channels()) {
			String value = value(c, channel);
			if (value != null) {
				suppressionService.suppress(channel, value, "UNSUBSCRIBE");
				done.add(channel);
			}
		}
		return new UnsubscribeResponse(done, OffsetDateTime.now(SEOUL));
	}

	private Customer find(String token) {
		return unsubscribeTokens.verify(token)
			.map(p -> customerMapper.findById(p.customerId()))
			.orElseThrow(() -> new BusinessException(CustomerErrorCode.UNSUBSCRIBE_INVALID_TOKEN));
	}

	private static String value(Customer c, Channel channel) {
		return channel == Channel.EMAIL ? c.getEmail() : c.getPhone();
	}
}
