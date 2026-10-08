package com.withus.customer.service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * SNS 로 나가는 HTTP 호출 (서명 인증서 받기, 구독 확인). 주소 검증은 호출하는 쪽(SesWebhookService)이 먼저 한다.
 * 로컬·테스트는 SNS 를 받을 수 없어 이 빈을 Mock 으로 바꾼다 (PRD 8.2)
 */
@Component
public class SnsHttpClient {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
	// ponytail: 만료 없는 캐시. AWS 는 인증서를 바꾸면 SigningCertURL 도 바뀌므로 주소별로 한 번만 받는다
	private final Map<String, PublicKey> keys = new ConcurrentHashMap<>();

	public PublicKey publicKey(String certUrl) throws IOException, InterruptedException, CertificateException {
		PublicKey cached = keys.get(certUrl);
		if (cached != null) {
			return cached;
		}
		HttpResponse<InputStream> res = http.send(request(certUrl), HttpResponse.BodyHandlers.ofInputStream());
		try (InputStream body = res.body()) {
			if (res.statusCode() != 200) {
				throw new IOException("SNS 인증서 응답 " + res.statusCode());
			}
			PublicKey key = CertificateFactory.getInstance("X.509").generateCertificate(body).getPublicKey();
			keys.put(certUrl, key);
			return key;
		}
	}

	/** SubscriptionConfirmation 의 SubscribeURL 을 열면 구독이 확정된다 */
	public void confirm(String subscribeUrl) throws IOException, InterruptedException {
		int status = http.send(request(subscribeUrl), HttpResponse.BodyHandlers.discarding()).statusCode();
		if (status != 200) {
			throw new IOException("SNS 구독 확인 응답 " + status);
		}
	}

	private static HttpRequest request(String url) {
		return HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build();
	}
}
