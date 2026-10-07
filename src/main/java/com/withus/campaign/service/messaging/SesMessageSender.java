package com.withus.campaign.service.messaging;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import com.withus.common.domain.Channel;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.MimeMessage;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.LimitExceededException;
import software.amazon.awssdk.services.sesv2.model.RawMessage;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;
import software.amazon.awssdk.services.sesv2.model.TooManyRequestsException;

/**
 * withus.mail.type=ses 일 때만 활성화되는 운영 메일 발송. List-Unsubscribe 헤더(원클릭 수신거부)를 넣어야 해서
 * Simple 이 아닌 Raw(MIME) 전송을 쓴다 (PL 승인, 이슈 #78).
 * 오류 분류: 요청 제한(429)·5xx·네트워크 단절은 TRANSIENT(1·5·15분 재시도), 그 밖의 4xx(MessageRejected,
 * MailFromDomainNotVerified 등)와 주소 형식 오류는 PERMANENT. 응답이 유실돼 SENDING 으로 남은 건은
 * 여기서 다루지 않고 멈춤 복구(SendRecoveryJob)가 UNKNOWN_RESULT 로 처리한다(중복보다 누락)
 */
@Component
@ConditionalOnProperty(prefix = "withus.mail", name = "type", havingValue = "ses")
public class SesMessageSender implements MessageSender {

	private final SesV2Client ses;
	private final String fromAddress;

	public SesMessageSender(SesV2Client ses, @Value("${withus.sender.from-address}") String fromAddress) {
		this.ses = ses;
		this.fromAddress = fromAddress;
	}

	@Override
	public Channel channel() {
		return Channel.EMAIL;
	}

	@Override
	public SendResult send(OutboundMessage message) {
		SdkBytes raw;
		try {
			raw = SdkBytes.fromByteArray(toRawMime(message));
		} catch (MessagingException | IOException e) { // AddressException 포함 — 조립 단계 오류는 재시도해도 같다
			return SendResult.failure(ErrorType.PERMANENT, e.getMessage());
		}

		try {
			String messageId = ses.sendEmail(SendEmailRequest.builder()
				.content(EmailContent.builder().raw(RawMessage.builder().data(raw).build()).build())
				.build()).messageId();
			return SendResult.success(messageId); // SNS 반송 웹훅이 provider_message_id 로 고객을 찾는다
		} catch (SesV2Exception e) {
			boolean transientError = e instanceof TooManyRequestsException || e instanceof LimitExceededException
				|| e.isThrottlingException() || e.statusCode() >= 500;
			return SendResult.failure(transientError ? ErrorType.TRANSIENT : ErrorType.PERMANENT, e.getMessage());
		} catch (SdkClientException e) {
			return SendResult.failure(ErrorType.TRANSIENT, e.getMessage()); // 연결 실패·타임아웃
		} catch (RuntimeException e) {
			return SendResult.failure(ErrorType.TRANSIENT, e.getMessage());
		}
	}

	private byte[] toRawMime(OutboundMessage message) throws MessagingException, IOException {
		MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()));
		MimeMessageHelper helper = new MimeMessageHelper(mime, false, "UTF-8");
		helper.setFrom(fromAddress);
		helper.setTo(message.to());
		helper.setSubject(message.subject());
		helper.setText(message.body(), true);
		for (Map.Entry<String, String> header : message.headers().entrySet()) {
			mime.setHeader(header.getKey(), header.getValue());
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		mime.writeTo(out);
		return out.toByteArray();
	}

	/** 자격증명은 DefaultCredentialsProvider(EC2 IAM 역할 우선), 리전은 AWS_REGION. 키는 코드·설정에 두지 않는다 */
	@Configuration
	@ConditionalOnProperty(prefix = "withus.mail", name = "type", havingValue = "ses")
	static class SesClientConfig {

		@Bean(destroyMethod = "close")
		SesV2Client sesV2Client() {
			return SesV2Client.builder()
				.credentialsProvider(DefaultCredentialsProvider.builder().build())
				// 응답이 10분 넘게 지연되면 멈춤 복구(UNKNOWN_RESULT)와 겹친다 — 그 안에 끊는다
				.overrideConfiguration(ClientOverrideConfiguration.builder()
					.apiCallTimeout(Duration.ofSeconds(60)).build())
				.build();
		}
	}
}
