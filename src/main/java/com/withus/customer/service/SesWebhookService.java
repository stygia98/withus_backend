package com.withus.customer.service;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.withus.campaign.service.SendQueueService;
import com.withus.common.domain.Channel;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * SES 반송·스팸신고 웹훅 (PRD 8.2, API_SPEC 9장). 인증 없이 열려 있으므로 SNS 서명과 토픽 ARN 을 확인한 요청만 처리한다
 * - 검증 실패·형식 오류는 로그만 남기고 무시한다 (응답은 항상 200)
 * - 영구 반송(Permanent)·스팸신고 수신자 이메일 → suppression 추가 + 동의 N (SuppressionService). 일시 반송은 무시
 * - 영구 반송이면 원 발송 건(mail.messageId = provider_message_id)을 BOUNCED 로 기록한다 (send_log 는 팀원2 소유라 SendQueueService 로)
 * - 외부 호출(인증서·구독 확인)은 트랜잭션 밖에서 한다
 */
@Service
public class SesWebhookService {

	private static final Logger log = LoggerFactory.getLogger(SesWebhookService.class);

	/** SigningCertURL·SubscribeURL 은 이 호스트의 https 만 믿는다 */
	private static final Pattern SNS_HOST = Pattern.compile("sns\\.[a-z0-9-]+\\.amazonaws\\.com");

	/** 서명 대상 키 (알파벳순, 값이 있는 것만). Subject 는 Notification 에만 있다 */
	private static final List<String> NOTIFICATION_KEYS = List.of("Message", "MessageId", "Subject", "Timestamp",
		"TopicArn", "Type");
	private static final List<String> SUBSCRIPTION_KEYS = List.of("Message", "MessageId", "SubscribeURL",
		"Timestamp", "Token", "TopicArn", "Type");

	private final ObjectMapper objectMapper;
	private final SnsHttpClient snsHttpClient;
	private final SuppressionService suppressionService;
	private final SendQueueService sendQueueService;
	private final String topicArn;

	public SesWebhookService(ObjectMapper objectMapper, SnsHttpClient snsHttpClient,
		SuppressionService suppressionService, SendQueueService sendQueueService,
		@Value("${ses.topic-arn:}") String topicArn) {
		this.objectMapper = objectMapper;
		this.snsHttpClient = snsHttpClient;
		this.suppressionService = suppressionService;
		this.sendQueueService = sendQueueService;
		this.topicArn = topicArn;
	}

	public void handle(String body) {
		JsonNode sns;
		try {
			sns = objectMapper.readTree(body);
		} catch (JacksonException e) {
			log.warn("SES 웹훅: JSON 이 아닌 요청 무시");
			return;
		}
		String type = text(sns, "Type");
		if (!verified(sns, type)) {
			return;
		}
		try {
			if ("SubscriptionConfirmation".equals(type)) {
				confirm(text(sns, "SubscribeURL"));
			} else if ("Notification".equals(type)) {
				notification(objectMapper.readTree(text(sns, "Message")));
			}
		} catch (JacksonException | IllegalArgumentException e) {
			log.warn("SES 웹훅: 처리할 수 없는 {} 무시 ({})", type, e.getMessage());
		}
	}

	private void confirm(String subscribeUrl) {
		if (!isSnsUrl(subscribeUrl)) {
			log.warn("SES 웹훅: SNS 가 아닌 SubscribeURL 무시");
			return;
		}
		try {
			snsHttpClient.confirm(subscribeUrl);
			log.info("SES 웹훅: SNS 구독 확인 완료 topic={}", topicArn);
		} catch (IOException e) {
			log.warn("SES 웹훅: SNS 구독 확인 실패 ({})", e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void notification(JsonNode ses) {
		String kind = text(ses, "notificationType");
		String reason;
		JsonNode recipients;
		if ("Bounce".equals(kind) && "Permanent".equals(text(ses.path("bounce"), "bounceType"))) {
			reason = "BOUNCE";
			recipients = ses.path("bounce").path("bouncedRecipients");
		} else if ("Complaint".equals(kind)) {
			reason = "COMPLAINT";
			recipients = ses.path("complaint").path("complainedRecipients");
		} else {
			return;
		}
		// 수신자 주소 기준으로 거부한다: 고객이 아직 없거나 삭제됐어도 이후 등록·발송에서 걸러진다 (PRD 7장)
		// 법규상 더 중요한 suppression 을 먼저 처리한다
		for (JsonNode r : recipients) {
			String email = CustomerNormalizer.email(text(r, "emailAddress"));
			if (email != null && !email.isEmpty()) {
				suppressionService.suppress(Channel.EMAIL, email, reason);
			}
		}
		// 스팸신고는 send_log 를 SENT 로 둔다: BOUNCED 로 바꾸면 이미 집계된 오픈·클릭·전환이 사후에 빠진다 (PRD 8.2)
		if ("BOUNCE".equals(reason)) {
			markBounced(text(ses.path("mail"), "messageId"));
		}
	}

	/** send_log 갱신 실패가 suppression 이나 웹훅 응답(200)에 영향을 주지 않게 격리한다 */
	private void markBounced(String messageId) {
		if (messageId == null || messageId.isBlank()) {
			return;
		}
		try {
			sendQueueService.markBounced(messageId);
		} catch (RuntimeException e) {
			log.warn("SES 웹훅: send_log BOUNCED 반영 실패 messageId={} ({})", messageId, e.getMessage());
		}
	}

	/** 토픽 ARN 일치 + SigningCertURL 호스트 + SNS 서명 (SignatureVersion 1: SHA1, 2: SHA256) */
	private boolean verified(JsonNode sns, String type) {
		if (topicArn.isBlank() || !topicArn.equals(text(sns, "TopicArn"))) {
			log.warn("SES 웹훅: 등록하지 않은 토픽 무시");
			return false;
		}
		String certUrl = text(sns, "SigningCertURL");
		String algorithm = switch (String.valueOf(text(sns, "SignatureVersion"))) {
			case "1" -> "SHA1withRSA";
			case "2" -> "SHA256withRSA";
			default -> null;
		};
		if (algorithm == null || !isSnsUrl(certUrl) || text(sns, "Signature") == null) {
			log.warn("SES 웹훅: 서명 정보가 올바르지 않은 요청 무시");
			return false;
		}
		List<String> keys = "Notification".equals(type) ? NOTIFICATION_KEYS : SUBSCRIPTION_KEYS;
		StringBuilder signed = new StringBuilder();
		for (String key : keys) {
			String value = text(sns, key);
			if (value != null) {
				signed.append(key).append('\n').append(value).append('\n');
			}
		}
		try {
			Signature verifier = Signature.getInstance(algorithm);
			verifier.initVerify(snsHttpClient.publicKey(certUrl));
			verifier.update(signed.toString().getBytes(StandardCharsets.UTF_8));
			if (verifier.verify(Base64.getDecoder().decode(text(sns, "Signature")))) {
				return true;
			}
			log.warn("SES 웹훅: 서명 불일치 요청 무시");
		} catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
			log.warn("SES 웹훅: 서명 검증 실패 ({})", e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return false;
	}

	static boolean isSnsUrl(String url) {
		try {
			URI uri = url == null ? null : URI.create(url);
			return uri != null && "https".equals(uri.getScheme()) && uri.getHost() != null
				&& SNS_HOST.matcher(uri.getHost()).matches();
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	private static String text(JsonNode node, String field) {
		JsonNode v = node.get(field);
		return v != null && v.isString() ? v.stringValue() : null;
	}
}
