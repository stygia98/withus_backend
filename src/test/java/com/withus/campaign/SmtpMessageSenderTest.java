package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.withus.campaign.service.messaging.ErrorType;
import com.withus.campaign.service.messaging.OutboundMessage;
import com.withus.campaign.service.messaging.SendResult;
import com.withus.campaign.service.messaging.SmtpMessageSender;
import com.withus.common.domain.Channel;

/**
 * SMTP 연결·주소 오류 분류 — Mailpit(Docker) 없이도 실행 가능하다
 * (성공 발송·Mailpit 도착 확인은 로컬에서 docker compose 로 별도 확인)
 */
class SmtpMessageSenderTest {

	private SmtpMessageSender sender(String host, int port) {
		JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
		mailSender.setHost(host);
		mailSender.setPort(port);
		return new SmtpMessageSender(mailSender, "withus@test.local");
	}

	@Test
	void SMTP_연결_실패는_TRANSIENT() {
		// 127.0.0.1:1 은 예약 포트라 즉시 연결이 거부된다
		SmtpMessageSender sender = sender("127.0.0.1", 1);

		SendResult result = sender.send(new OutboundMessage(Channel.EMAIL, "me@withus.local", "제목", "본문", Map.of()));

		assertThat(result.success()).isFalse();
		assertThat(result.errorType()).isEqualTo(ErrorType.TRANSIENT);
	}

	@Test
	void 잘못된_수신_주소는_PERMANENT() {
		SmtpMessageSender sender = sender("127.0.0.1", 1); // 연결 전에 주소 파싱에서 실패해야 한다

		SendResult result = sender.send(
			new OutboundMessage(Channel.EMAIL, "<a@b.com", "제목", "본문", Map.of())); // 닫는 '>' 없는 잘못된 형식

		assertThat(result.success()).isFalse();
		assertThat(result.errorType()).isEqualTo(ErrorType.PERMANENT);
	}
}
