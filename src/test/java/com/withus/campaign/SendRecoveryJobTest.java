package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.withus.auth.domain.Member;
import com.withus.auth.domain.Role;
import com.withus.auth.mapper.MemberMapper;
import com.withus.campaign.domain.SendKind;
import com.withus.campaign.domain.SendLog;
import com.withus.campaign.domain.SendStatus;
import com.withus.campaign.mapper.SendLogMapper;
import com.withus.campaign.service.SendRecoveryJob;
import com.withus.common.domain.Channel;

/**
 * SENDING 10분 초과 복구 (발송 큐 Plan 10장, DB_SCHEMA 7장)
 * 로컬 Docker DB 가 떠 있어야 한다. 백그라운드 스케줄러는 끄고 recover() 를 직접 호출해 검증한다
 */
// setUp()에서 PENDING 으로 잠깐 적재되는 틈을 배경 SendDispatcher 가 가로채지 않도록 둘 다 끈다
@SpringBootTest(properties = { "withus.scheduler.send-recovery.enabled=false",
	"withus.scheduler.send-dispatcher.enabled=false" })
class SendRecoveryJobTest {

	@Autowired
	SendRecoveryJob sendRecoveryJob;
	@Autowired
	SendLogMapper sendLogMapper;
	@Autowired
	MemberMapper memberMapper;
	@Autowired
	JdbcTemplate jdbcTemplate;

	Long memberId;
	Long segmentId;
	Long campaignId;
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

		jdbcTemplate.update("INSERT INTO segment (name, created_by) VALUES (?, ?)", "세그먼트", memberId);
		segmentId = jdbcTemplate.queryForObject("SELECT max(segment_id) FROM segment", Long.class);

		jdbcTemplate.update(
			"INSERT INTO campaign (name, type, status, segment_id, created_by) VALUES (?, 'ONE_TIME', 'ACTIVE', ?, ?)",
			"캠페인", segmentId, memberId);
		campaignId = jdbcTemplate.queryForObject("SELECT max(campaign_id) FROM campaign", Long.class);

		jdbcTemplate.update(
			"INSERT INTO customer (email, joined_at, source) VALUES (?, now(), 'MANUAL')",
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
		jdbcTemplate.update("DELETE FROM customer WHERE customer_id = ?", customerId);
		jdbcTemplate.update("DELETE FROM member WHERE member_id = ?", memberId);
	}

	@Test
	void SENDING이_10분_넘게_남으면_FAILED_UNKNOWN_RESULT로_바뀌고_재발송되지_않는다() {
		jdbcTemplate.update(
			"UPDATE send_log SET status = 'SENDING', updated_at = now() - interval '11 minutes' WHERE send_log_id = ?",
			sendLogId);

		sendRecoveryJob.recover();

		String status = jdbcTemplate.queryForObject(
			"SELECT status FROM send_log WHERE send_log_id = ?", String.class, sendLogId);
		String errorMessage = jdbcTemplate.queryForObject(
			"SELECT error_message FROM send_log WHERE send_log_id = ?", String.class, sendLogId);
		assertThat(status).isEqualTo("FAILED");
		assertThat(errorMessage).isEqualTo("UNKNOWN_RESULT");

		List<SendLog> claimed = sendLogMapper.claimBatch();
		assertThat(claimed).extracting(SendLog::getSendLogId)
			.as("FAILED 로 복구된 건은 재발송 대상(PENDING)이 아니다").doesNotContain(sendLogId);
	}

	@Test
	void SENDING이_10분_안쪽이면_건드리지_않는다() {
		jdbcTemplate.update(
			"UPDATE send_log SET status = 'SENDING', updated_at = now() - interval '5 minutes' WHERE send_log_id = ?",
			sendLogId);

		sendRecoveryJob.recover();

		String status = jdbcTemplate.queryForObject(
			"SELECT status FROM send_log WHERE send_log_id = ?", String.class, sendLogId);
		assertThat(status).isEqualTo("SENDING");
	}
}
