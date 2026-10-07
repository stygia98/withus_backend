package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.withus.campaign.domain.SendKind;
import com.withus.campaign.service.SendDispatcher;
import com.withus.campaign.service.SendQueueService;
import com.withus.common.domain.Channel;

/**
 * F-12 NOTICE 가 적재 → 발송 큐 → SENT 까지 가는지 확인한다 (PL 완료 기준, 이슈 #81). NOTICE 는 시간창(08:00~20:50)이
 * 적용되므로 테스트에서만 창을 하루 종일로 열어 실행 시각과 무관하게 한다.
 * 로컬 Docker DB + Mailpit(SMTP) 이 떠 있어야 한다. 디스패처는 자체 커밋 트랜잭션을 쓰므로 @Transactional 없이 직접 정리한다
 */
@SpringBootTest(properties = {
	"withus.scheduler.send-dispatcher.enabled=false",
	"withus.send-window.start=00:00:00",
	"withus.send-window.end=23:59:59"
})
class SendDispatcherNoticeTest {

	@Autowired
	SendDispatcher sendDispatcher;
	@Autowired
	SendQueueService sendQueueService;
	@Autowired
	JdbcTemplate jdbcTemplate;

	final List<Long> customerIds = new ArrayList<>();

	@AfterEach
	void cleanUp() {
		for (Long customerId : customerIds) {
			jdbcTemplate.update("DELETE FROM send_log WHERE customer_id = ?", customerId);
			jdbcTemplate.update("DELETE FROM customer WHERE customer_id = ?", customerId);
		}
	}

	/** 수신동의 일시를 직접 지정한 고객. consentAtSql 이 null 이면 동의 Y 인데 일시가 비어 있는 고객이다 */
	private long newCustomer(String consentAtSql) {
		jdbcTemplate.update("INSERT INTO customer (email, joined_at, source, email_consent_yn, email_consent_at) "
			+ "VALUES (?, now(), 'MANUAL', 'Y', " + (consentAtSql == null ? "NULL" : consentAtSql) + ")",
			"notice-" + UUID.randomUUID() + "@withus.local");
		long customerId = jdbcTemplate.queryForObject("SELECT max(customer_id) FROM customer", Long.class);
		customerIds.add(customerId);
		return customerId;
	}

	@Test
	void 동의_일시가_있는_고객의_NOTICE는_템플릿_없이_렌더링되어_SENT가_된다() {
		long customerId = newCustomer("now() - interval '2 years'");
		sendQueueService.enqueueOneTime(null, List.of(customerId), Channel.EMAIL, SendKind.NOTICE);

		sendDispatcher.dispatch();

		var row = jdbcTemplate.queryForMap(
			"SELECT status, kind, campaign_id, template_id, provider_message_id, error_message "
				+ "FROM send_log WHERE customer_id = ? AND kind = 'NOTICE'", customerId);
		assertThat(row).containsEntry("status", "SENT").containsEntry("campaign_id", null)
			.containsEntry("template_id", null);
		assertThat(row.get("provider_message_id")).isNotNull();
	}

	@Test
	void 동의_일시를_찾을_수_없으면_재시도하지_않고_SKIPPED로_끝난다() {
		long customerId = newCustomer(null);
		sendQueueService.enqueueOneTime(null, List.of(customerId), Channel.EMAIL, SendKind.NOTICE);

		sendDispatcher.dispatch();

		var row = jdbcTemplate.queryForMap(
			"SELECT status, error_message, attempt_count FROM send_log WHERE customer_id = ? AND kind = 'NOTICE'",
			customerId);
		assertThat(row).containsEntry("status", "SKIPPED").containsEntry("error_message", "CONSENT_DATE_MISSING")
			.containsEntry("attempt_count", 0);
	}
}
