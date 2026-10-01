package com.withus.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.withus.ai.domain.HourlyEngagement;
import com.withus.ai.mapper.SendTimeMapper;

/**
 * AI-02 집계 SQL: 한국 시각 기준 요일·시, 봇·TEST 제외 (CLAUDE.md 6장 8번). 로컬 Docker DB, 롤백.
 * 기존 데이터와 섞이지 않도록 2031년 이벤트만 넣고 그 이후만 집계한다.
 */
@SpringBootTest
@Transactional
class SendTimeMapperTest {

	@Autowired
	SendTimeMapper mapper;
	@Autowired
	JdbcTemplate jdbc;

	private long send(String kind) {
		long customerId = jdbc.queryForObject("""
			INSERT INTO customer (name, email, joined_at, source) VALUES ('시간', ?, DATE '2031-01-01', 'MANUAL') RETURNING customer_id
			""", Long.class, "st-" + UUID.randomUUID() + "@example.com");
		return jdbc.queryForObject("""
			INSERT INTO send_log (customer_id, recipient, channel, kind, priority, status)
			VALUES (?, 'st@withus.local', 'EMAIL', ?, ?, 'SENT') RETURNING send_log_id
			""", Long.class, customerId, kind, "TEST".equals(kind) ? 1 : 3);
	}

	private void event(long sendLogId, String type, String botYn, String occurredAt) {
		jdbc.update("INSERT INTO track_event (send_log_id, event_type, bot_yn, occurred_at) VALUES (?, ?, ?, CAST(? AS timestamptz))",
			sendLogId, type, botYn, occurredAt);
	}

	@Test
	void 한국_시각_기준_요일_시로_묶고_봇과_TEST는_뺀다() {
		long campaign = send("CAMPAIGN");
		long test = send("TEST");
		event(campaign, "OPEN", "N", "2031-03-04 10:15:00+09");
		event(campaign, "CLICK", "N", "2031-03-04 01:30:00+00"); // UTC 01:30 = 한국 10:30 → 같은 칸
		event(campaign, "CLICK", "Y", "2031-03-04 10:40:00+09"); // 봇
		event(test, "OPEN", "N", "2031-03-04 10:50:00+09"); // 테스트 발송

		List<HourlyEngagement> rows = mapper.engagementByDayHour(OffsetDateTime.parse("2031-01-01T00:00:00+09:00"));

		assertThat(rows).singleElement().satisfies(r -> {
			assertThat(r.getIsoDayOfWeek()).isEqualTo(LocalDate.of(2031, 3, 4).getDayOfWeek().getValue());
			assertThat(r.getHour()).isEqualTo(10);
			assertThat(r.getOpens()).isEqualTo(1);
			assertThat(r.getClicks()).isEqualTo(1);
		});
	}
}
