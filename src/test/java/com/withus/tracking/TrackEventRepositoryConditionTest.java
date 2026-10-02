package com.withus.tracking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.withus.tracking.service.TrackEventRepository;

/**
 * 워크플로우 CONDITION(EMAIL_OPENED·EMAIL_CLICKED)이 쓰는 existsHumanEvent 판정 (PRD 6.3·6.5-4, workflow-plan 3.2)
 * 엔진은 직전 EMAIL send_log 하나를 골라 이 메서드로 YES/NO 를 정한다. 저장 규칙은 TrackingEventServiceTest 가 맡고,
 * 여기서는 이벤트 행을 직접 넣어 분기 판정만 확인한다. 로컬 Docker DB 를 쓰고 테스트마다 롤백한다
 */
@SpringBootTest
@Transactional
class TrackEventRepositoryConditionTest {

	@Autowired
	TrackEventRepository repository;
	@Autowired
	JdbcTemplate jdbc;

	OffsetDateTime now;

	@BeforeEach
	void setUp() {
		now = OffsetDateTime.now();
	}

	@Test
	void SKIPPED_FAILED_건은_이벤트가_없어_NO() {
		long skipped = insertSendLog("CAMPAIGN", "SKIPPED");
		long failed = insertSendLog("CAMPAIGN", "FAILED");

		assertThat(repository.existsHumanEvent(skipped, "OPEN")).isFalse();
		assertThat(repository.existsHumanEvent(skipped, "CLICK")).isFalse();
		assertThat(repository.existsHumanEvent(failed, "OPEN")).isFalse();
		assertThat(repository.existsHumanEvent(failed, "CLICK")).isFalse();
	}

	@Test
	void 오픈만_있으면_오픈은_YES_클릭은_NO() {
		long sendLogId = insertSendLog("CAMPAIGN", "SENT");
		insertEvent(sendLogId, "OPEN", "N");

		assertThat(repository.existsHumanEvent(sendLogId, "OPEN")).isTrue();
		assertThat(repository.existsHumanEvent(sendLogId, "CLICK")).isFalse();
	}

	@Test
	void 봇_이벤트_뒤에_사람_이벤트가_있으면_YES() {
		long sendLogId = insertSendLog("CAMPAIGN", "SENT");
		insertEvent(sendLogId, "CLICK", "Y");
		insertEvent(sendLogId, "CLICK", "N");

		assertThat(repository.existsHumanEvent(sendLogId, "CLICK")).isTrue();
	}

	@Test
	void 다른_발송_건의_이벤트는_판정에_섞이지_않는다() {
		// 같은 고객이 앞 단계 메일을 클릭했어도 직전 메일 판정에는 영향이 없어야 한다
		long earlier = insertSendLog("CAMPAIGN", "SENT");
		long latest = insertSendLog("CAMPAIGN", "SENT");
		insertEvent(earlier, "CLICK", "N");

		assertThat(repository.existsHumanEvent(earlier, "CLICK")).isTrue();
		assertThat(repository.existsHumanEvent(latest, "CLICK")).isFalse();
	}

	@Test
	void NOTICE_발송의_사람_이벤트는_NO() {
		long notice = insertSendLog("NOTICE", "SENT");
		insertEvent(notice, "OPEN", "N");
		insertEvent(notice, "CLICK", "N");

		assertThat(repository.existsHumanEvent(notice, "OPEN")).isFalse();
		assertThat(repository.existsHumanEvent(notice, "CLICK")).isFalse();
	}

	// ---- 픽스처 ----

	private long insertSendLog(String kind, String status) {
		OffsetDateTime sentAt = "SENT".equals(status) ? now.minusHours(1) : null;
		return jdbc.queryForObject("""
			INSERT INTO send_log (recipient, channel, kind, priority, status, sent_at)
			VALUES ('condition-test@withus.local', 'EMAIL', ?, 3, ?, ?)
			RETURNING send_log_id
			""", Long.class, kind, status, sentAt);
	}

	private void insertEvent(long sendLogId, String eventType, String botYn) {
		jdbc.update("INSERT INTO track_event (send_log_id, event_type, bot_yn, occurred_at) VALUES (?, ?, ?, ?)",
			sendLogId, eventType, botYn, now);
	}
}
