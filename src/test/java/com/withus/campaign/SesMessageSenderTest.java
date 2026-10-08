package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.withus.campaign.service.messaging.ErrorType;
import com.withus.campaign.service.messaging.OutboundMessage;
import com.withus.campaign.service.messaging.SendResult;
import com.withus.campaign.service.messaging.SesMessageSender;
import com.withus.common.domain.Channel;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.MessageRejectedException;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;
import software.amazon.awssdk.services.sesv2.model.TooManyRequestsException;

/** SDK 클라이언트를 목킹해 오류 분류·헤더 조립만 검증한다 — AWS 를 호출하지 않는다 (실제 SES 는 W5 샌드박스에서 수동 확인) */
class SesMessageSenderTest {

	private final SesV2Client ses = mock(SesV2Client.class);
	private final SesMessageSender sender = new SesMessageSender(ses, "hello@withus.test");

	private OutboundMessage message(String to) {
		return new OutboundMessage(Channel.EMAIL, to, "제목", "<p>본문</p>",
			Map.of("List-Unsubscribe", "<https://withus.test/unsubscribe/abc>"));
	}

	@Test
	void 성공하면_MessageId를_돌려주고_헤더가_MIME에_들어간다() {
		when(ses.sendEmail(any(SendEmailRequest.class)))
			.thenReturn(SendEmailResponse.builder().messageId("ses-123").build());

		SendResult result = sender.send(message("me@withus.test"));

		assertThat(result.success()).isTrue();
		assertThat(result.providerMessageId()).isEqualTo("ses-123");
		ArgumentCaptor<SendEmailRequest> captor = ArgumentCaptor.forClass(SendEmailRequest.class);
		verify(ses).sendEmail(captor.capture());
		String mime = new String(captor.getValue().content().raw().data().asByteArray(), StandardCharsets.UTF_8);
		assertThat(mime).contains("List-Unsubscribe: <https://withus.test/unsubscribe/abc>")
			.contains("To: me@withus.test");
	}

	@Test
	void 요청_제한은_TRANSIENT() {
		when(ses.sendEmail(any(SendEmailRequest.class)))
			.thenThrow(TooManyRequestsException.builder().message("slow down").build());

		assertThat(sender.send(message("me@withus.test")).errorType()).isEqualTo(ErrorType.TRANSIENT);
	}

	@Test
	void 서버_5xx는_TRANSIENT() {
		when(ses.sendEmail(any(SendEmailRequest.class)))
			.thenThrow((SesV2Exception) SesV2Exception.builder().statusCode(503).message("unavailable").build());

		assertThat(sender.send(message("me@withus.test")).errorType()).isEqualTo(ErrorType.TRANSIENT);
	}

	@Test
	void 네트워크_오류는_TRANSIENT() {
		when(ses.sendEmail(any(SendEmailRequest.class))).thenThrow(SdkClientException.create("connect timeout"));

		assertThat(sender.send(message("me@withus.test")).errorType()).isEqualTo(ErrorType.TRANSIENT);
	}

	@Test
	void MessageRejected는_PERMANENT() {
		when(ses.sendEmail(any(SendEmailRequest.class)))
			.thenThrow(MessageRejectedException.builder().message("rejected").build());

		SendResult result = sender.send(message("me@withus.test"));

		assertThat(result.success()).isFalse();
		assertThat(result.errorType()).isEqualTo(ErrorType.PERMANENT);
	}

	@Test
	void 잘못된_수신_주소는_SES_호출_없이_PERMANENT() {
		SendResult result = sender.send(message("<a@b.com"));

		assertThat(result.errorType()).isEqualTo(ErrorType.PERMANENT);
		verifyNoInteractions(ses);
	}
}
