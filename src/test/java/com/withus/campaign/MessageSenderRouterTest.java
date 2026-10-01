package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.withus.campaign.service.messaging.MessageSenderRouter;
import com.withus.campaign.service.messaging.OutboundMessage;
import com.withus.campaign.service.messaging.SendResult;
import com.withus.campaign.service.messaging.SmsMockSender;
import com.withus.common.domain.Channel;

/** 라우팅과 SMS Mock — DB·Spring 컨텍스트 없이 실행 */
class MessageSenderRouterTest {

	@Test
	void SMS_Mock은_성공과_providerMessageId를_돌려준다() {
		SendResult result = new SmsMockSender().send(
			new OutboundMessage(Channel.SMS, "01012345678", null, "본문", java.util.Map.of()));

		assertThat(result.success()).isTrue();
		assertThat(result.providerMessageId()).isNotBlank();
		assertThat(result.errorType()).isNull();
	}

	@Test
	void 라우터는_채널에_맞는_구현체를_고른다() {
		MessageSenderRouter router = new MessageSenderRouter(List.of(new SmsMockSender()));

		SendResult result = router.send(new OutboundMessage(Channel.SMS, "01012345678", null, "본문", java.util.Map.of()));

		assertThat(result.success()).isTrue();
	}

	@Test
	void 등록되지_않은_채널이면_예외() {
		MessageSenderRouter router = new MessageSenderRouter(List.of(new SmsMockSender()));

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> router.send(
				new OutboundMessage(Channel.EMAIL, "a@b.com", "제목", "본문", java.util.Map.of())))
			.isInstanceOf(IllegalStateException.class);
	}
}
