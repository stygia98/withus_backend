package com.withus.campaign.service.messaging;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import com.withus.common.domain.Channel;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.MimeMessage;

/**
 * withus.mail.type=smtp 일 때만 활성화. local 은 Mailpit(1025), 운영 SES 구현은 W4
 * MailSendException 은 send() 직전 단계(연결·전송)에서 나므로 원인(cause)에 주소 형식 오류(AddressException)가
 * 있으면 PERMANENT, 그 외(연결 실패 등)는 TRANSIENT 로 본다. AddressException 은 helper.setTo() 에서
 * 바로 던져지기도 한다(명백한 입력 오류라 PERMANENT)
 */
@Component
@ConditionalOnProperty(prefix = "withus.mail", name = "type", havingValue = "smtp")
public class SmtpMessageSender implements MessageSender {

	private final JavaMailSender mailSender;
	private final String fromAddress;

	public SmtpMessageSender(JavaMailSender mailSender, @Value("${withus.sender.from-address}") String fromAddress) {
		this.mailSender = mailSender;
		this.fromAddress = fromAddress;
	}

	@Override
	public Channel channel() {
		return Channel.EMAIL;
	}

	@Override
	public SendResult send(OutboundMessage message) {
		try {
			MimeMessage mime = mailSender.createMimeMessage();
			MimeMessageHelper helper = new MimeMessageHelper(mime, false, "UTF-8");
			helper.setFrom(fromAddress);
			helper.setTo(message.to());
			helper.setSubject(message.subject());
			helper.setText(message.body(), true);
			for (Map.Entry<String, String> header : message.headers().entrySet()) {
				mime.setHeader(header.getKey(), header.getValue());
			}
			mime.saveChanges(); // Message-ID 가 아직 없으면 이때 생성된다
			String messageId = mime.getMessageID();

			mailSender.send(mime);
			return SendResult.success(messageId);
		} catch (AddressException e) {
			return SendResult.failure(ErrorType.PERMANENT, e.getMessage());
		} catch (MailAuthenticationException e) {
			return SendResult.failure(ErrorType.PERMANENT, e.getMessage());
		} catch (MailSendException e) {
			return SendResult.failure(causedByAddressException(e) ? ErrorType.PERMANENT : ErrorType.TRANSIENT,
				e.getMessage());
		} catch (MessagingException | RuntimeException e) {
			return SendResult.failure(ErrorType.TRANSIENT, e.getMessage());
		}
	}

	private boolean causedByAddressException(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof AddressException) {
				return true;
			}
		}
		return false;
	}
}
