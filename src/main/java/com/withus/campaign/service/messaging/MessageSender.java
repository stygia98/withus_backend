package com.withus.campaign.service.messaging;

import com.withus.common.domain.Channel;

/**
 * 실제 발송 채널 연동 (local: SMTP·SMS Mock / prod: SES·SMS, W4). 서비스 코드에서 직접 호출하지 않는다 —
 * 발송 큐(SendDispatcher, W2)만 호출한다 (CLAUDE.md 6장 1번)
 */
public interface MessageSender {

	Channel channel();

	SendResult send(OutboundMessage message);
}
