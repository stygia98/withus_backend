package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.withus.auth.domain.Member;
import com.withus.auth.domain.Role;
import com.withus.auth.mapper.MemberMapper;
import com.withus.campaign.domain.SendKind;
import com.withus.campaign.domain.SendLog;
import com.withus.campaign.domain.SendStatus;
import com.withus.campaign.mapper.SendLogMapper;
import com.withus.campaign.service.SendDispatcher;
import com.withus.campaign.service.messaging.ErrorType;
import com.withus.campaign.service.messaging.MessageSenderRouter;
import com.withus.campaign.service.messaging.SendResult;
import com.withus.common.domain.Channel;

/**
 * 재시도 1·5·15분과 3회 초과 FAILED (발송 큐 Plan 8장) — MessageSenderRouter 를 모의 오류로 대체해 검증한다.
 * 로컬 Docker DB 가 떠 있어야 한다(Mailpit 은 필요 없다, 실제 발송을 안 하므로). @Transactional 을 쓰지 않는다:
 * SendDispatcher 의 결과 기록이 각각 단건 커밋이라 실제 커밋을 봐야 검증된다. 끝나고 직접 정리한다
 */
@SpringBootTest(properties = "withus.scheduler.send-dispatcher.enabled=false")
class SendDispatcherRetryTest {

	@Autowired
	SendDispatcher sendDispatcher;
	@Autowired
	SendLogMapper sendLogMapper;
	@Autowired
	MemberMapper memberMapper;
	@Autowired
	JdbcTemplate jdbcTemplate;
	@MockitoBean
	MessageSenderRouter messageSenderRouter;

	Long memberId;
	Long segmentId;
	Long campaignId;
	Long templateId;
	Long customerId;
	Long sendLogId;

	@BeforeEach
	void setUp() {
		Member member = new Member();
		member.setEmail("test-" + UUID.randomUUID() + "@withus.local");
		member.setPassword("x");
		member.setName("테스트");
		member.setRole(Role.MANAGER);
		memberMapper.insert(member);
		memberId = member.getMemberId();

		jdbcTemplate.update("INSERT INTO template (channel, name, subject, body, ad_yn, created_by) "
			+ "VALUES ('EMAIL', ?, '제목', '본문', 'N', ?)", "재시도 테스트 템플릿", memberId);
		templateId = jdbcTemplate.queryForObject("SELECT max(template_id) FROM template", Long.class);

		jdbcTemplate.update("INSERT INTO segment (name, created_by) VALUES (?, ?)", "세그먼트", memberId);
		segmentId = jdbcTemplate.queryForObject("SELECT max(segment_id) FROM segment", Long.class);

		jdbcTemplate.update(
			"INSERT INTO campaign (name, type, status, segment_id, template_id, created_by) "
				+ "VALUES (?, 'ONE_TIME', 'ACTIVE', ?, ?, ?)",
			"캠페인", segmentId, templateId, memberId);
		campaignId = jdbcTemplate.queryForObject("SELECT max(campaign_id) FROM campaign", Long.class);

		jdbcTemplate.update(
			"INSERT INTO customer (email, joined_at, source, email_consent_yn) VALUES (?, now(), 'MANUAL', 'Y')",
			"customer-" + UUID.randomUUID() + "@withus.local");
		customerId = jdbcTemplate.queryForObject("SELECT max(customer_id) FROM customer", Long.class);

		sendLogMapper.insertOneTimeBatch(List.of(SendLog.builder()
			.campaignId(campaignId)
			.customerId(customerId)
			.recipient("customer@withus.local")
			.channel(Channel.EMAIL)
			.status(SendStatus.PENDING)
			.kind(SendKind.CAMPAIGN)
			.priority(SendLog.PRIORITY_CAMPAIGN_BULK)
			.build()));
		sendLogId = jdbcTemplate.queryForObject(
			"SELECT send_log_id FROM send_log WHERE campaign_id = ? AND customer_id = ?", Long.class, campaignId,
			customerId);
	}

	@AfterEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM send_log WHERE campaign_id = ?", campaignId);
		jdbcTemplate.update("DELETE FROM campaign WHERE campaign_id = ?", campaignId);
		jdbcTemplate.update("DELETE FROM segment WHERE segment_id = ?", segmentId);
		jdbcTemplate.update("DELETE FROM template WHERE template_id = ?", templateId);
		jdbcTemplate.update("DELETE FROM customer WHERE customer_id = ?", customerId);
		jdbcTemplate.update("DELETE FROM member WHERE member_id = ?", memberId);
	}

	private SendLog attempt(int attemptCount) {
		return SendLog.builder()
			.sendLogId(sendLogId)
			.campaignId(campaignId)
			.customerId(customerId)
			.channel(Channel.EMAIL)
			.kind(SendKind.CAMPAIGN)
			.attemptCount(attemptCount)
			.build();
	}

	@Test
	void 일시_오류는_1_5_15분_뒤_재시도하고_네번째는_FAILED() {
		when(messageSenderRouter.send(any())).thenReturn(SendResult.failure(ErrorType.TRANSIENT, "모의 스로틀링"));

		sendDispatcher.processOne(attempt(0)); // 1번째 실패 → attempt_count 1
		assertRetryScheduledWithin(1, 1);

		sendDispatcher.processOne(attempt(1)); // 2번째 실패 → attempt_count 2
		assertRetryScheduledWithin(2, 5);

		sendDispatcher.processOne(attempt(2)); // 3번째 실패 → attempt_count 3
		assertRetryScheduledWithin(3, 15);

		sendDispatcher.processOne(attempt(3)); // 4번째 실패 → 재시도 없이 FAILED
		String status = jdbcTemplate.queryForObject(
			"SELECT status FROM send_log WHERE send_log_id = ?", String.class, sendLogId);
		assertThat(status).isEqualTo("FAILED");
	}

	private void assertRetryScheduledWithin(int expectedAttemptCount, int expectedMinutes) {
		String status = jdbcTemplate.queryForObject(
			"SELECT status FROM send_log WHERE send_log_id = ?", String.class, sendLogId);
		int attemptCount = jdbcTemplate.queryForObject(
			"SELECT attempt_count FROM send_log WHERE send_log_id = ?", Integer.class, sendLogId);
		OffsetDateTime nextAttemptAt = jdbcTemplate.queryForObject(
			"SELECT next_attempt_at FROM send_log WHERE send_log_id = ?", OffsetDateTime.class, sendLogId);

		assertThat(status).isEqualTo("PENDING");
		assertThat(attemptCount).isEqualTo(expectedAttemptCount);
		OffsetDateTime expected = OffsetDateTime.now().plusMinutes(expectedMinutes);
		assertThat(ChronoUnit.SECONDS.between(expected, nextAttemptAt)).as("next_attempt_at 이 now+%d분 근처여야 한다",
			expectedMinutes).isBetween(-5L, 5L);
	}
}
