package com.withus.campaign.service.messaging;

import java.util.Map;

import com.withus.common.domain.Channel;

/** 발송 직전 렌더링이 끝난 메시지 (MessageComposer 의 결과물, W2) */
public record OutboundMessage(Channel channel, String to, String subject, String body, Map<String, String> headers) {
}
