package com.withus.customer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.withus.customer.service.DormantBatch;

/** 휴면 판정 (PRD F-11). 로컬 Docker DB, 테스트마다 롤백 */
@SpringBootTest
@Transactional
class DormantBatchTest {

	@Autowired
	DormantBatch batch;
	@Autowired
	JdbcTemplate jdbc;

	@Test
	void 가입_180일_지나고_클릭_구매_없으면_휴면() {
		long id = customer(200, "N");
		batch.run();
		assertThat(dormant(id)).isEqualTo("Y");
		assertThat(jdbc.queryForObject("SELECT dormant_at IS NOT NULL FROM customer WHERE customer_id = ?",
			Boolean.class, id)).isTrue();
	}

	@Test
	void 가입_180일_안_된_고객은_판정하지_않는다() {
		long id = customer(179, "N");
		batch.run();
		assertThat(dormant(id)).isEqualTo("N");
	}

	@Test
	void 최근_180일_사람_클릭이나_구매가_있으면_휴면_아님() {
		long clicked = customer(200, "N");
		event(clicked, "CAMPAIGN", "CLICK", "N", 10);
		long purchased = customer(200, "N");
		jdbc.update("INSERT INTO purchase (customer_id, amount, created_by, purchased_at) "
			+ "VALUES (?, 10000, ?, now() - INTERVAL '10 days')", purchased, member());

		batch.run();

		assertThat(dormant(clicked)).isEqualTo("N");
		assertThat(dormant(purchased)).isEqualTo("N");
	}

	@Test
	void 봇_클릭_오픈_180일_전_클릭_NOTICE_클릭은_활동으로_보지_않는다() {
		long bot = customer(200, "N");
		event(bot, "CAMPAIGN", "CLICK", "Y", 10);
		long open = customer(200, "N");
		event(open, "CAMPAIGN", "OPEN", "N", 10);
		long old = customer(400, "N");
		event(old, "CAMPAIGN", "CLICK", "N", 181);
		long notice = customer(200, "N");
		event(notice, "NOTICE", "CLICK", "N", 10);

		batch.run();

		assertThat(dormant(bot)).isEqualTo("Y");
		assertThat(dormant(open)).isEqualTo("Y");
		assertThat(dormant(old)).isEqualTo("Y");
		assertThat(dormant(notice)).isEqualTo("Y");
	}

	@Test
	void 휴면_고객이_다시_클릭하면_해제된다() {
		long id = customer(300, "Y");
		event(id, "CAMPAIGN", "CLICK", "N", 1);
		batch.run();
		assertThat(dormant(id)).isEqualTo("N");
		assertThat(jdbc.queryForObject("SELECT dormant_at FROM customer WHERE customer_id = ?", Object.class, id))
			.isNull();
	}

	@Test
	void 삭제된_고객은_건드리지_않는다() {
		long id = customer(200, "N");
		jdbc.update("UPDATE customer SET deleted_yn = 'Y' WHERE customer_id = ?", id);
		batch.run();
		assertThat(dormant(id)).isEqualTo("N");
	}

	@Test
	void 새벽_3시_이후_그날_첫_회차에만_실행한다() {
		LocalDate today = LocalDate.of(2026, 10, 2);
		assertThat(DormantBatch.shouldRun(today.atTime(2, 59), null)).isFalse();
		assertThat(DormantBatch.shouldRun(today.atTime(3, 0), null)).isTrue();
		assertThat(DormantBatch.shouldRun(today.atTime(14, 0), today.minusDays(1))).isTrue();
		assertThat(DormantBatch.shouldRun(today.atTime(3, 10), today)).isFalse();
	}

	private long customer(int joinedDaysAgo, String dormant) {
		return jdbc.queryForObject("""
			INSERT INTO customer (email, joined_at, dormant_yn, dormant_at, source)
			VALUES (?, CURRENT_DATE - ?, ?, CASE WHEN ? = 'Y' THEN now() END, 'MANUAL') RETURNING customer_id
			""", Long.class, "dormant-" + UUID.randomUUID() + "@withus.local", joinedDaysAgo, dormant, dormant);
	}

	private void event(long customerId, String kind, String eventType, String bot, int daysAgo) {
		Long sendLogId = jdbc.queryForObject("""
			INSERT INTO send_log (customer_id, recipient, channel, status, kind, priority, sent_at)
			VALUES (?, 'r@withus.local', 'EMAIL', 'SENT', ?, 2, now()) RETURNING send_log_id
			""", Long.class, customerId, kind);
		jdbc.update("INSERT INTO track_event (send_log_id, event_type, bot_yn, occurred_at) "
			+ "VALUES (?, ?, ?, now() - make_interval(days => ?))", sendLogId, eventType, bot, daysAgo);
	}

	private long member() {
		return jdbc.queryForObject("INSERT INTO member (email, password, name, role) VALUES (?, 'x', '테스트', 'STAFF') "
			+ "RETURNING member_id", Long.class, "m-" + UUID.randomUUID() + "@withus.local");
	}

	private String dormant(long id) {
		return jdbc.queryForObject("SELECT dormant_yn FROM customer WHERE customer_id = ?", String.class, id);
	}
}
