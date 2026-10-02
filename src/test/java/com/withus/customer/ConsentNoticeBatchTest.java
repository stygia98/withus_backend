package com.withus.customer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.withus.customer.service.ConsentNoticeBatch;

/** 수신동의 2년 주기 확인 안내 (PRD F-12). 로컬 Docker DB(시드 포함), 테스트마다 롤백. 결과는 테스트 고객 기준으로만 본다 */
@SpringBootTest
@Transactional
class ConsentNoticeBatchTest {

	static final String OVER = "2 years 1 day";
	static final String UNDER = "2 years -1 day";

	@Autowired
	ConsentNoticeBatch batch;
	@Autowired
	JdbcTemplate jdbc;

	@Test
	void 동의_2년이_지나면_NOTICE_로_적재하고_2년_전이면_적재하지_않는다() {
		long over = customer(OVER, null);
		long under = customer(UNDER, null);

		batch.run();

		List<Map<String, Object>> rows = notices(over);
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0)).containsEntry("channel", "EMAIL").containsEntry("status", "PENDING")
			.containsEntry("priority", 2).containsEntry("campaign_id", null);
		assertThat(notices(under)).isEmpty();
	}

	@Test
	void 채널마다_따로_센다_이메일_안내가_SMS_기준일을_밀지_않는다() {
		long id = customer(OVER, OVER);
		sentNotice(id, "EMAIL", "1 year");

		batch.run();

		// 이메일은 1년 전에 안내했으므로 대상 아님, SMS 는 동의 후 2년이 지났으므로 대상
		assertThat(notices(id)).extracting(r -> r.get("channel") + "/" + r.get("status"))
			.containsExactlyInAnyOrder("EMAIL/SENT", "SMS/PENDING");
	}

	@Test
	void 직전_안내로부터_2년이_지나면_다시_안내한다() {
		long id = customer("5 years", null);
		sentNotice(id, "EMAIL", OVER);

		batch.run();

		assertThat(notices(id)).extracting(r -> r.get("status")).containsExactlyInAnyOrder("SENT", "PENDING");
	}

	@Test
	void 동의_N_삭제_고객은_제외하고_다시_돌려도_보류_중인_안내를_또_넣지_않는다() {
		long rejected = customer(OVER, null);
		jdbc.update("UPDATE customer SET email_consent_yn = 'N' WHERE customer_id = ?", rejected);
		long deleted = customer(OVER, null);
		jdbc.update("UPDATE customer SET deleted_yn = 'Y' WHERE customer_id = ?", deleted);
		long id = customer(OVER, null);

		batch.run();
		batch.run();

		assertThat(notices(rejected)).isEmpty();
		assertThat(notices(deleted)).isEmpty();
		assertThat(notices(id)).hasSize(1);
	}

	@Test
	void SENT_된_NOTICE_로_직전_안내_일시를_갱신한다_멱등() {
		long id = customer("3 years", null);
		sentNotice(id, "EMAIL", "3 days");
		sentNotice(id, "SMS", "1 day");

		batch.run();
		Object first = notifiedAt(id);
		batch.run();

		assertThat(first).isNotNull();
		assertThat(notifiedAt(id)).isEqualTo(first);
		assertThat(jdbc.queryForObject("SELECT consent_notified_at = (SELECT max(sent_at) FROM send_log "
			+ "WHERE customer_id = ? AND kind = 'NOTICE') FROM customer WHERE customer_id = ?", Boolean.class, id, id))
			.isTrue();
	}

	/** 이메일 동의는 emailAgo 전, SMS 동의는 smsAgo 전(null 이면 SMS 동의 N) */
	private long customer(String emailAgo, String smsAgo) {
		return jdbc.queryForObject("INSERT INTO customer (email, phone, joined_at, source, email_consent_yn, "
			+ "email_consent_at, sms_consent_yn, sms_consent_at) VALUES (?, ?, CURRENT_DATE, 'MANUAL', 'Y', "
			+ "now() - CAST(? AS INTERVAL), ?, now() - CAST(? AS INTERVAL)) RETURNING customer_id", Long.class,
			"notice-" + UUID.randomUUID() + "@withus.local", "010" + phoneDigits(), emailAgo,
			smsAgo == null ? "N" : "Y", smsAgo == null ? "0 days" : smsAgo);
	}

	private void sentNotice(long customerId, String channel, String ago) {
		jdbc.update("INSERT INTO send_log (customer_id, recipient, channel, status, kind, priority, sent_at) "
			+ "VALUES (?, 'x', ?, 'SENT', 'NOTICE', 2, now() - CAST(? AS INTERVAL))", customerId, channel, ago);
	}

	private List<Map<String, Object>> notices(long customerId) {
		return jdbc.queryForList("SELECT channel, status, priority::int AS priority, campaign_id FROM send_log "
			+ "WHERE customer_id = ? AND kind = 'NOTICE'", customerId);
	}

	private Object notifiedAt(long customerId) {
		return jdbc.queryForObject("SELECT consent_notified_at FROM customer WHERE customer_id = ?", Object.class,
			customerId);
	}

	/** 시드 고객 휴대폰과 겹치지 않게 UUID 에서 8자리 (휴대폰 정규화 규칙: 숫자만) */
	private static String phoneDigits() {
		return String.format("%08d", Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 100_000_000L));
	}
}
