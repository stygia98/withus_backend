package com.withus.campaign.service.messaging;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.withus.common.domain.Channel;

/**
 * 채널별 MessageSender 를 고르는 유일한 지점. 등록된 MessageSender 빈을 모아 채널로 매핑한다
 * (Spring 은 enum 키로 바로 주입할 수 없어, 각 구현체가 담당 채널을 밝히고 여기서 Map 으로 모은다)
 */
@Component
public class MessageSenderRouter {

	private final Map<Channel, MessageSender> senders;

	public MessageSenderRouter(List<MessageSender> senders) {
		this.senders = senders.stream().collect(Collectors.toMap(MessageSender::channel, Function.identity()));
	}

	public SendResult send(OutboundMessage message) {
		MessageSender sender = senders.get(message.channel());
		if (sender == null) {
			throw new IllegalStateException("등록된 MessageSender 가 없습니다: " + message.channel());
		}
		return sender.send(message);
	}
}
