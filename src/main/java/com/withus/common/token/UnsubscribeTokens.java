package com.withus.common.token;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 수신거부 토큰 생성·검증 (PRD 8.3, API_SPEC 9장).
 * 토큰 = Base64URL("send_log_id:customer_id:HMAC-SHA256"). 이메일 등 고객 정보는 넣지 않는다.
 * 생성은 발송 렌더링(팀원2), 검증은 수신거부 API(팀원1)가 같은 키·형식으로 쓰도록 공용으로 둔다.
 */
@Component
public class UnsubscribeTokens {

	private static final String ALGORITHM = "HmacSHA256";
	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
	private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

	private final SecretKeySpec key;

	public UnsubscribeTokens(@Value("${withus.unsubscribe.hmac-secret}") String secret) {
		if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
			throw new IllegalStateException("HMAC_SECRET 은 32자 이상이어야 합니다");
		}
		this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
	}

	/** 검증된 토큰의 내용 */
	public record Payload(long sendLogId, long customerId) {
	}

	public String issue(long sendLogId, long customerId) {
		String data = sendLogId + ":" + customerId;
		return ENCODER.encodeToString((data + ":" + sign(data)).getBytes(StandardCharsets.UTF_8));
	}

	/** 형식이 틀리거나 서명이 맞지 않으면 empty. 실패 사유는 구분하지 않는다 (고객 정보 비노출, PRD 8.3) */
	public Optional<Payload> verify(String token) {
		try {
			String[] parts = new String(DECODER.decode(token), StandardCharsets.UTF_8).split(":", -1);
			if (parts.length != 3) {
				return Optional.empty();
			}
			long sendLogId = Long.parseLong(parts[0]);
			long customerId = Long.parseLong(parts[1]);
			String expected = sign(sendLogId + ":" + customerId);
			// 시간 차 공격을 막으려고 상수 시간 비교를 쓴다
			boolean valid = MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
				parts[2].getBytes(StandardCharsets.UTF_8));
			return valid ? Optional.of(new Payload(sendLogId, customerId)) : Optional.empty();
		} catch (IllegalArgumentException | NullPointerException e) {
			// Base64 형식 오류, 숫자 아님(NumberFormatException 포함), null
			return Optional.empty();
		}
	}

	private String sign(String data) {
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(key);
			return ENCODER.encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException | InvalidKeyException e) {
			throw new IllegalStateException("HMAC 서명에 실패했습니다", e);
		}
	}
}
