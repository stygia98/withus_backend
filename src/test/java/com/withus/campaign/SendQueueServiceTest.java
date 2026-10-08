package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
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
import com.withus.campaign.service.SendQueueService;
import com.withus.common.domain.Channel;

/**
 * 발송 큐 적재 (발송 큐 Plan 2장) — 멱등 적재, 수신동의 N 은 SKIPPED, 500건 배치 분할, kind 별 priority.
 * @Transactional 을 쓰지 않는다: enqueueOneTime 은 호출자가 트랜잭션 밖에서 부르는 것이 계약이고(청크마다
 * 짧게 커밋, 긴 트랜잭션 금지) 실제 호출자도 그렇다 — 같은 조건으로 실제 커밋을 검증하고 끝나고 직접
 * 정리한다. 백그라운드 SendDispatcher 가 적재된 PENDING 을 가져가지 않도록 끈다
 */
@SpringBootTest(properties = "withus.scheduler.send-dispatcher.enabled=false")
class SendQueueServiceTest {

	@Autowired
	SendQueueService sendQueueService;
	@Autowired
	MemberMapper memberMapper;
	@Autowired
	JdbcTemplate jdbcTemplate;

	long memberId;
	final List<Long> campaignIds = new ArrayList<>();
	final List<Long> segmentIds = new ArrayList<>();
	final List<Long> customerIds = new ArrayList<>();

	@BeforeEach
	void setUp() {
		Member member = new Member();
		member.setEmail("test-" + UUID.randomUUID() + "@withus.local");
		member.setPassword("x");
		member.setName("테스트");
		member.setRole(Role.MANAGER);
		memberMapper.insert(member);
		memberId = member.getMemberId();
	}

	@AfterEach
	void cleanUp() {
		for (Long campaignId : campaignIds) {
			jdbcTemplate.update("DELETE FROM send_log WHERE campaign_id = ?", campaignId);
			jdbcTemplate.update("DELETE FROM workflow_instance WHERE campaign_id = ?", campaignId);
			jdbcTemplate.update("DELETE FROM workflow_step WHERE campaign_id = ?", campaignId);
			jdbcTemplate.update("DELETE FROM campaign WHERE campaign_id = ?", campaignId);
		}
		for (Long segmentId : segmentIds) {
			jdbcTemplate.update("DELETE FROM segment WHERE segment_id = ?", segmentId);
		}
		for (Long customerId : customerIds) {
			jdbcTemplate.update("DELETE FROM send_log WHERE customer_id = ?", customerId);
			jdbcTemplate.update("DELETE FROM customer WHERE customer_id = ?", customerId);
		}
		jdbcTemplate.update("DELETE FROM member WHERE member_id = ?", memberId);
	}

	private long newCustomer(String emailConsentYn) {
		long customerId = jdbcTemplate.queryForObject("""
			INSERT INTO customer (email, joined_at, source, email_consent_yn) VALUES (?, now(), 'MANUAL', ?)
			RETURNING customer_id
			""", Long.class, "customer-" + UUID.randomUUID() + "@withus.local", emailConsentYn);
		customerIds.add(customerId);
		return customerId;
	}

	private long newSegment() {
		long segmentId = jdbcTemplate.queryForObject(
			"INSERT INTO segment (name, created_by) VALUES (?, ?) RETURNING segment_id", Long.class, "세그먼트",
			memberId);
		segmentIds.add(segmentId);
		return segmentId;
	}

	private long newOneTimeCampaign() {
		long campaignId = jdbcTemplate.queryForObject("""
			INSERT INTO campaign (name, type, status, segment_id, created_by)
			VALUES (?, 'ONE_TIME', 'ACTIVE', ?, ?) RETURNING campaign_id
			""", Long.class, "캠페인", newSegment(), memberId);
		campaignIds.add(campaignId);
		return campaignId;
	}

	private long newWorkflowStep() {
		long campaignId = jdbcTemplate.queryForObject("""
			INSERT INTO campaign (name, type, status, segment_id, trigger_type, created_by)
			VALUES (?, 'WORKFLOW', 'ACTIVE', ?, 'CUSTOMER_REGISTERED', ?) RETURNING campaign_id
			""", Long.class, "워크플로우 캠페인", newSegment(), memberId);
		campaignIds.add(campaignId);
		return jdbcTemplate.queryForObject(
			"INSERT INTO workflow_step (campaign_id, node_type) VALUES (?, 'SEND_EMAIL') RETURNING step_id",
			Long.class, campaignId);
	}

	@Test
	void 같은_캠페인을_두번_적재해도_고객당_1건이다() {
		long campaignId = newOneTimeCampaign();
		long customerId = newCustomer("Y");

		int first = sendQueueService.enqueueOneTime(campaignId, List.of(customerId), Channel.EMAIL,
			SendKind.CAMPAIGN);
		int second = sendQueueService.enqueueOneTime(campaignId, List.of(customerId), Channel.EMAIL,
			SendKind.CAMPAIGN);

		assertThat(first).isEqualTo(1);
		assertThat(second).isEqualTo(0);
		Long count = jdbcTemplate.queryForObject(
			"SELECT count(*) FROM send_log WHERE campaign_id = ? AND customer_id = ?", Long.class, campaignId,
			customerId);
		assertThat(count).isEqualTo(1);
	}

	@Test
	void 수신동의_N인_고객은_SKIPPED로_적재된다() {
		long campaignId = newOneTimeCampaign();
		long customerId = newCustomer("N");

		sendQueueService.enqueueOneTime(campaignId, List.of(customerId), Channel.EMAIL, SendKind.CAMPAIGN);

		String status = jdbcTemplate.queryForObject(
			"SELECT status FROM send_log WHERE campaign_id = ? AND customer_id = ?", String.class, campaignId,
			customerId);
		assertThat(status).isEqualTo("SKIPPED");
		String reason = jdbcTemplate.queryForObject(
			"SELECT error_message FROM send_log WHERE campaign_id = ? AND customer_id = ?", String.class,
			campaignId, customerId);
		assertThat(reason).isEqualTo("NOT_SENDABLE");
	}

	@Test
	void 천이백명을_적재하면_오백건씩_세번에_나눠_전부_쌓인다() {
		long campaignId = newOneTimeCampaign();
		List<Long> targets = new ArrayList<>();
		for (int i = 0; i < 1200; i++) {
			targets.add(newCustomer("Y"));
		}

		int inserted = sendQueueService.enqueueOneTime(campaignId, targets, Channel.EMAIL, SendKind.CAMPAIGN);

		assertThat(inserted).isEqualTo(1200);
		Long count = jdbcTemplate.queryForObject(
			"SELECT count(*) FROM send_log WHERE campaign_id = ?", Long.class, campaignId);
		assertThat(count).isEqualTo(1200);
	}

	@Test
	void priority는_kind로_정해진다() {
		long campaignId = newOneTimeCampaign();
		long customerId = newCustomer("Y");

		sendQueueService.enqueueOneTime(campaignId, List.of(customerId), Channel.EMAIL, SendKind.CAMPAIGN);

		Short priority = jdbcTemplate.queryForObject(
			"SELECT priority FROM send_log WHERE campaign_id = ? AND customer_id = ?", Short.class, campaignId,
			customerId);
		assertThat(priority).as("일회성·대량 발송은 priority 3").isEqualTo((short) 3);
	}

	@Test
	void 같은_워크플로우_스텝을_두번_적재해도_1건이다() {
		long stepId = newWorkflowStep();
		long campaignId = jdbcTemplate.queryForObject(
			"SELECT campaign_id FROM workflow_step WHERE step_id = ?", Long.class, stepId);
		long customerId = newCustomer("Y");
		long instanceId = jdbcTemplate.queryForObject("""
			INSERT INTO workflow_instance (campaign_id, customer_id, current_step_id) VALUES (?, ?, ?)
			RETURNING instance_id
			""", Long.class, campaignId, customerId, stepId);

		int first = sendQueueService.enqueueWorkflowStep(campaignId, instanceId, stepId, customerId, Channel.EMAIL);
		int second = sendQueueService.enqueueWorkflowStep(campaignId, instanceId, stepId, customerId, Channel.EMAIL);

		assertThat(first).isEqualTo(1);
		assertThat(second).isEqualTo(0);
	}
}
