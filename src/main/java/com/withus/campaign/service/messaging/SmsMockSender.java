package com.withus.campaign.service.messaging;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.withus.common.domain.Channel;

/** withus.sms.type=mock 일 때만 활성화. 실제 발송 없이 로그만 남기고 성공으로 처리한다 */
@Component
@ConditionalOnProperty(prefix = "withus.sms", name = "type", havingValue = "mock")
public class SmsMockSender implements MessageSender {

	private static final Logger log = LoggerFactory.getLogger(SmsMockSender.class);

	@Override
	public Channel channel() {
		return Channel.SMS;
	}

	@Override
	public SendResult send(OutboundMessage message) {
		String providerMessageId = UUID.randomUUID().toString();
		log.info("[SMS Mock] to={} providerMessageId={} body={}", message.to(), providerMessageId, message.body());
		return SendResult.success(providerMessageId);
	}
}
