package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
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
import com.withus.common.domain.Channel;

/**
 * 선점 동시성 (발송 큐 Plan 3.1, DB_SCHEMA 7장) — FOR UPDATE SKIP LOCKED 가 실제로 중복 선점을 막는지 검증
 * @Transactional 을 안 쓴다: 두 스레드가 서로 다른 DB 커넥션으로 동시에 들어가야 하기 때문에, 각 테스트가
 * 만든 데이터는 끝나고 직접 지운다(자동 롤백이 없다)
 */
@SpringBootTest
class SendLogMapperConcurrencyTest {

	@Autowired
	SendLogMapper sendLogMapper;
	@Autowired
	MemberMapper memberMapper;
	@Autowired
	JdbcTemplate jdbcTemplate;

	Long campaignId;
	Long segmentId;
	Long memberId;
	final List<Long> customerIds = new ArrayList<>();

	@AfterEach
	void cleanUp() {
		if (campaignId != null) {
			jdbcTemplate.update("DELETE FROM send_log WHERE campaign_id = ?", campaignId);
			jdbcTemplate.update("DELETE FROM campaign WHERE campaign_id = ?", campaignId);
		}
		if (segmentId != null) {
			jdbcTemplate.update("DELETE FROM segment WHERE segment_id = ?", segmentId);
		}
		for (Long customerId : customerIds) {
			jdbcTemplate.update("DELETE FROM customer WHERE customer_id = ?", customerId);
		}
		if (memberId != null) {
			jdbcTemplate.update("DELETE FROM member WHERE member_id = ?", memberId);
		}
	}

	@Test
	void 두_스레드가_동시에_선점해도_같은_행을_두번_잡지_않는다() throws Exception {
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

		List<SendLog> logs = new ArrayList<>();
		for (int i = 0; i < 80; i++) {
			jdbcTemplate.update(
				"INSERT INTO customer (email, joined_at, source) VALUES (?, now(), 'MANUAL')",
				"customer-" + UUID.randomUUID() + "@withus.local");
			long customerId = jdbcTemplate.queryForObject("SELECT max(customer_id) FROM customer", Long.class);
			customerIds.add(customerId);
			logs.add(SendLog.builder()
				.campaignId(campaignId)
				.customerId(customerId)
				.recipient("customer@withus.local")
				.channel(Channel.EMAIL)
				.status(SendStatus.PENDING)
				.kind(SendKind.CAMPAIGN)
				.priority(SendLog.PRIORITY_CAMPAIGN_BULK)
				.build());
		}
		sendLogMapper.insertOneTimeBatch(logs);

		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<List<SendLog>> first = executor.submit(sendLogMapper::claimBatch);
			Future<List<SendLog>> second = executor.submit(sendLogMapper::claimBatch);

			List<Long> firstIds = idsOf(first.get());
			List<Long> secondIds = idsOf(second.get());

			assertThat(firstIds).as("두 스레드가 겹치는 행이 없어야 한다").doesNotContainAnyElementsOf(secondIds);
			assertThat(firstIds.size() + secondIds.size()).isEqualTo(80);
			Long sendingCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM send_log WHERE campaign_id = ? AND status = 'SENDING'", Long.class,
				campaignId);
			assertThat(sendingCount).isEqualTo(80L);
		} finally {
			executor.shutdown();
		}
	}

	private static List<Long> idsOf(List<SendLog> logs) {
		return logs.stream().map(SendLog::getSendLogId).collect(Collectors.toList());
	}
}
