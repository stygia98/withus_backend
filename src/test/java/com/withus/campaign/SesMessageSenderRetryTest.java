package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.withus.campaign.service.messaging.ErrorType;
import com.withus.campaign.service.messaging.OutboundMessage;
import com.withus.campaign.service.messaging.SendResult;
import com.withus.campaign.service.messaging.SesMessageSender;
import com.withus.common.domain.Channel;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;

/**
 * 실제 SesV2Client 설정(SesMessageSender.clientBuilder)을 로컬 가짜 서버에 연결해, SDK 가 같은 메일 요청을
 * 자동 재시도하지 않는지 확인한다 (PL 리뷰 #85). SES SendEmail 에는 멱등 키가 없어 재시도는 중복 발송이다.
 * 목킹 테스트는 SDK 의 재시도 계층을 거치지 않아 이걸 못 잡는다. AWS·DB 없이 실행된다
 */
class SesMessageSenderRetryTest {

	private HttpServer server;
	private final AtomicInteger requests = new AtomicInteger();
	private volatile boolean dropConnection;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", this::handle);
		server.start();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	/** 요청 본문을 끝까지 받은 뒤(= SES 가 메일을 받은 상황) 응답 없이 연결을 끊거나 500 을 돌려준다 */
	private void handle(HttpExchange exchange) throws IOException {
		requests.incrementAndGet();
		exchange.getRequestBody().readAllBytes();
		if (dropConnection) {
			exchange.close();
			return;
		}
		byte[] body = "{\"message\":\"internal\"}".getBytes();
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(500, body.length);
		exchange.getResponseBody().write(body);
		exchange.close();
	}

	private SendResult sendThroughRealClient() {
		try (SesV2Client client = SesMessageSender.clientBuilder()
			.endpointOverride(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
			.region(Region.AP_NORTHEAST_2)
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
			.build()) {
			return new SesMessageSender(client, "hello@withus.test")
				.send(new OutboundMessage(Channel.EMAIL, "me@withus.test", "제목", "<p>본문</p>", Map.of()));
		}
	}

	@Test
	void 응답이_유실돼도_같은_메일_요청을_다시_보내지_않는다() {
		dropConnection = true;

		SendResult result = sendThroughRealClient();

		assertThat(requests.get()).isEqualTo(1);
		assertThat(result.success()).isFalse();
		assertThat(result.errorType()).isEqualTo(ErrorType.TRANSIENT);
	}

	@Test
	void 서버_500에도_같은_메일_요청을_다시_보내지_않는다() {
		SendResult result = sendThroughRealClient();

		assertThat(requests.get()).isEqualTo(1);
		assertThat(result.success()).isFalse();
		assertThat(result.errorType()).isEqualTo(ErrorType.TRANSIENT);
	}
}
