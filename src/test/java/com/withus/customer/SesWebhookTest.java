package com.withus.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.withus.customer.service.SnsHttpClient;

import tools.jackson.databind.ObjectMapper;

/**
 * SES 웹훅 (PRD 8.2, API_SPEC 9장). 로컬은 SNS 를 받을 수 없어 SNS 형식 Mock 요청을 테스트 키로 서명해 보낸다.
 * 실제 SNS 서명 검증은 W5 배포 후 확인한다. 로컬 Docker DB, 테스트마다 롤백
 */
@SpringBootTest(properties = "ses.topic-arn=" + SesWebhookTest.TOPIC)
@AutoConfigureMockMvc
@Transactional
class SesWebhookTest {

	static final String TOPIC = "arn:aws:sns:ap-northeast-2:000000000000:withus-ses";
	static final String CERT_URL = "https://sns.ap-northeast-2.amazonaws.com/SimpleNotificationService-test.pem";

	static KeyPair keys;

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	ObjectMapper objectMapper;
	@MockitoBean
	SnsHttpClient sns;

	@BeforeAll
	static void keyPair() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		keys = generator.generateKeyPair();
	}

	@BeforeEach
	void setUp() throws Exception {
		when(sns.publicKey(anyString())).thenReturn(keys.getPublic());
	}

	@Test
	void 영구_반송은_수신자_주소를_거부하고_동의_N_이력_BOUNCE() throws Exception {
		String email = email();
		long id = customer(email);
		String unknown = email();
		String messageId = sentLog(id, email);

		send(notification("Bounce", """
			"mail":{"messageId":"%s"},
			"bounce":{"bounceType":"Permanent","bouncedRecipients":[{"emailAddress":"%s"},{"emailAddress":"%s"}]}
			""".formatted(messageId, email.toUpperCase(), unknown), "1"));

		assertThat(sendStatus(messageId)).isEqualTo("BOUNCED");
		assertThat(consent(id)).isEqualTo("N");
		assertThat(reason(email)).isEqualTo("BOUNCE");
		// 고객이 없는 주소도 목록에 남아 이후 등록·업로드 때 걸러진다
		assertThat(reason(unknown)).isEqualTo("BOUNCE");
		assertThat(jdbc.queryForObject("SELECT source FROM consent_history WHERE customer_id = ?", String.class, id))
			.isEqualTo("BOUNCE");
	}

	@Test
	void 스팸신고는_COMPLAINT_로_거부한다_서명버전2() throws Exception {
		String email = email();
		long id = customer(email);
		String messageId = sentLog(id, email);

		send(notification("Complaint", """
			"mail":{"messageId":"%s"},
			"complaint":{"complainedRecipients":[{"emailAddress":"%s"}]}
			""".formatted(messageId, email), "2"));

		assertThat(sendStatus(messageId)).isEqualTo("BOUNCED");
		assertThat(consent(id)).isEqualTo("N");
		assertThat(reason(email)).isEqualTo("COMPLAINT");
	}

	@Test
	void 일시_반송_위조_서명_다른_토픽_SNS_가_아닌_인증서는_무시하고_200() throws Exception {
		String email = email();
		long id = customer(email);
		String messageId = sentLog(id, email);
		String bounce = """
			"mail":{"messageId":"%s"},
			"bounce":{"bounceType":"%%s","bouncedRecipients":[{"emailAddress":"%s"}]}
			""".formatted(messageId, email);

		send(notification("Bounce", bounce.formatted("Transient"), "1"));

		Map<String, String> forged = notification("Bounce", bounce.formatted("Permanent"), "1");
		forged.put("Message", forged.get("Message").replace("Permanent", "Permanent "));
		send(forged);

		Map<String, String> otherTopic = new TreeMap<>(Map.of("TopicArn", TOPIC + "-other"));
		otherTopic.putAll(withoutSignature(notification("Bounce", bounce.formatted("Permanent"), "1"),
			"TopicArn"));
		send(sign(otherTopic, "1"));

		Map<String, String> evilCert = notification("Bounce", bounce.formatted("Permanent"), "1");
		evilCert.put("SigningCertURL", "https://sns.ap-northeast-2.amazonaws.com.evil.example/cert.pem");
		send(evilCert);

		mvc.perform(post("/api/webhooks/ses").contentType(MediaType.TEXT_PLAIN).content("not json"))
			.andExpect(status().isOk());

		assertThat(sendStatus(messageId)).isEqualTo("SENT");
		assertThat(consent(id)).isEqualTo("Y");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM suppression WHERE value = ?", Integer.class, email))
			.isZero();
		verify(sns, never()).publicKey("https://sns.ap-northeast-2.amazonaws.com.evil.example/cert.pem");
	}

	@Test
	void 구독_확인은_SNS_주소만_연다() throws Exception {
		String url = "https://sns.ap-northeast-2.amazonaws.com/?Action=ConfirmSubscription&Token=abc";
		send(subscription(url));
		verify(sns).confirm(url);

		String evil = "https://evil.example/?Action=ConfirmSubscription";
		send(subscription(evil));
		verify(sns, never()).confirm(evil);
	}

	private Map<String, String> notification(String type, String detail, String version) {
		Map<String, String> m = new TreeMap<>();
		m.put("Type", "Notification");
		m.put("MessageId", UUID.randomUUID().toString());
		m.put("TopicArn", TOPIC);
		m.put("Message", "{\"notificationType\":\"%s\",%s}".formatted(type, detail.strip()));
		m.put("Timestamp", "2026-10-01T06:00:00.000Z");
		return sign(m, version);
	}

	private Map<String, String> subscription(String subscribeUrl) {
		Map<String, String> m = new TreeMap<>();
		m.put("Type", "SubscriptionConfirmation");
		m.put("MessageId", UUID.randomUUID().toString());
		m.put("Token", "abc");
		m.put("TopicArn", TOPIC);
		m.put("Message", "You have chosen to subscribe to the topic");
		m.put("SubscribeURL", subscribeUrl);
		m.put("Timestamp", "2026-10-01T06:00:00.000Z");
		return sign(m, "1");
	}

	/** SNS 서명 문자열: 정해진 키를 알파벳순으로 "키\n값\n" (TreeMap 순서) */
	private static Map<String, String> sign(Map<String, String> fields, String version) {
		try {
			StringBuilder signed = new StringBuilder();
			fields.forEach((k, v) -> signed.append(k).append('\n').append(v).append('\n'));
			Signature signer = Signature.getInstance("1".equals(version) ? "SHA1withRSA" : "SHA256withRSA");
			signer.initSign(keys.getPrivate());
			signer.update(signed.toString().getBytes(StandardCharsets.UTF_8));
			Map<String, String> m = new LinkedHashMap<>(fields);
			m.put("SignatureVersion", version);
			m.put("Signature", Base64.getEncoder().encodeToString(signer.sign()));
			m.put("SigningCertURL", CERT_URL);
			return m;
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static Map<String, String> withoutSignature(Map<String, String> m, String alsoRemove) {
		Map<String, String> copy = new TreeMap<>(m);
		copy.keySet().removeAll(java.util.Set.of("SignatureVersion", "Signature", "SigningCertURL", alsoRemove));
		return copy;
	}

	private void send(Map<String, String> message) throws Exception {
		mvc.perform(post("/api/webhooks/ses").contentType(MediaType.TEXT_PLAIN)
			.content(objectMapper.writeValueAsString(message))).andExpect(status().isOk());
	}

	private long customer(String email) {
		return jdbc.queryForObject("INSERT INTO customer (email, joined_at, source, email_consent_yn, email_consent_at) "
			+ "VALUES (?, CURRENT_DATE, 'MANUAL', 'Y', now()) RETURNING customer_id", Long.class, email);
	}

	/** SES 로 나간 발송 건 (send_log 는 팀원2 소유지만 테스트 준비 데이터라 직접 넣는다). provider_message_id 를 돌려준다 */
	private String sentLog(long customerId, String email) {
		String messageId = "ses-" + UUID.randomUUID();
		jdbc.update("INSERT INTO send_log (customer_id, recipient, channel, status, kind, priority, provider_message_id, "
			+ "sent_at) VALUES (?, ?, 'EMAIL', 'SENT', 'NOTICE', 2, ?, now())", customerId, email, messageId);
		return messageId;
	}

	private String sendStatus(String messageId) {
		return jdbc.queryForObject("SELECT status FROM send_log WHERE provider_message_id = ?", String.class, messageId);
	}

	private String consent(long customerId) {
		return jdbc.queryForObject("SELECT email_consent_yn FROM customer WHERE customer_id = ?", String.class,
			customerId);
	}

	private String reason(String email) {
		return jdbc.queryForObject("SELECT reason FROM suppression WHERE channel = 'EMAIL' AND value = ?",
			String.class, email);
	}

	private static String email() {
		return "ses-" + UUID.randomUUID() + "@withus.local";
	}
}
