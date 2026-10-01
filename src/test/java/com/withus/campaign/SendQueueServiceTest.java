package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.withus.auth.domain.Member;
import com.withus.auth.domain.Role;
import com.withus.auth.mapper.MemberMapper;
import com.withus.campaign.domain.SendKind;
import com.withus.campaign.domain.SendLog;
import com.withus.campaign.service.SendQueueService;
import com.withus.common.domain.Channel;

/**
 * 발송 큐 적재 (발송 큐 Plan 2장) — 멱등 적재, 수신동의 N 은 SKIPPED, 500건 배치 분할
 * 로컬 Docker DB 를 쓰고 테스트마다 롤백한다
 */
@SpringBootTest
@Transactional
class SendQueueServiceTest {

	@Autowired
	SendQueueService sendQueueService;
	@Autowired
	MemberMapper memberMapper;
	@Autowired
	JdbcTemplate jdbcTemplate;

	long memberId;

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

	private long newCustomer(String emailConsentYn) {
		jdbcTemplate.update(
			"INSERT INTO customer (email, joined_at, source, email_consent_yn) VALUES (?, now(), 'MANUAL', ?)",
			"customer-" + UUID.randomUUID() + "@withus.local", emailConsentYn);
		return jdbcTemplate.queryForObject("SELECT max(customer_id) FROM customer", Long.class);
	}

	private long newSegment() {
		jdbcTemplate.update("INSERT INTO segment (name, created_by) VALUES (?, ?)", "세그먼트", memberId);
		return jdbcTemplate.queryForObject("SELECT max(segment_id) FROM segment", Long.class);
	}

	private long newOneTimeCampaign() {
		jdbcTemplate.update(
			"INSERT INTO campaign (name, type, status, segment_id, created_by) VALUES (?, 'ONE_TIME', 'ACTIVE', ?, ?)",
			"캠페인", newSegment(), memberId);
		return jdbcTemplate.queryForObject("SELECT max(campaign_id) FROM campaign", Long.class);
	}

	private long newWorkflowStep() {
		jdbcTemplate.update(
			"INSERT INTO campaign (name, type, status, segment_id, trigger_type, created_by) "
				+ "VALUES (?, 'WORKFLOW', 'ACTIVE', ?, 'CUSTOMER_REGISTERED', ?)",
			"워크플로우 캠페인", newSegment(), memberId);
		long campaignId = jdbcTemplate.queryForObject("SELECT max(campaign_id) FROM campaign", Long.class);
		jdbcTemplate.update("INSERT INTO workflow_step (campaign_id, node_type) VALUES (?, 'SEND_EMAIL')", campaignId);
		return jdbcTemplate.queryForObject("SELECT max(step_id) FROM workflow_step", Long.class);
	}

	@Test
	void 같은_캠페인을_두번_적재해도_고객당_1건이다() {
		long campaignId = newOneTimeCampaign();
		long customerId = newCustomer("Y");

		int first = sendQueueService.enqueueOneTime(campaignId, List.of(customerId), Channel.EMAIL,
			SendKind.CAMPAIGN, SendLog.PRIORITY_CAMPAIGN_BULK);
		int second = sendQueueService.enqueueOneTime(campaignId, List.of(customerId), Channel.EMAIL,
			SendKind.CAMPAIGN, SendLog.PRIORITY_CAMPAIGN_BULK);

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

		sendQueueService.enqueueOneTime(campaignId, List.of(customerId), Channel.EMAIL, SendKind.CAMPAIGN,
			SendLog.PRIORITY_CAMPAIGN_BULK);

		String status = jdbcTemplate.queryForObject(
			"SELECT status FROM send_log WHERE campaign_id = ? AND customer_id = ?", String.class, campaignId,
			customerId);
		assertThat(status).isEqualTo("SKIPPED");
	}

	@Test
	void 천이백명을_적재하면_오백건씩_세번에_나눠_전부_쌓인다() {
		long campaignId = newOneTimeCampaign();
		List<Long> customerIds = new ArrayList<>();
		for (int i = 0; i < 1200; i++) {
			customerIds.add(newCustomer("Y"));
		}

		int inserted = sendQueueService.enqueueOneTime(campaignId, customerIds, Channel.EMAIL, SendKind.CAMPAIGN,
			SendLog.PRIORITY_CAMPAIGN_BULK);

		assertThat(inserted).isEqualTo(1200);
		Long count = jdbcTemplate.queryForObject(
			"SELECT count(*) FROM send_log WHERE campaign_id = ?", Long.class, campaignId);
		assertThat(count).isEqualTo(1200);
	}

	@Test
	void 같은_워크플로우_스텝을_두번_적재해도_1건이다() {
		long stepId = newWorkflowStep();
		long campaignId = jdbcTemplate.queryForObject(
			"SELECT campaign_id FROM workflow_step WHERE step_id = ?", Long.class, stepId);
		long customerId = newCustomer("Y");
		jdbcTemplate.update(
			"INSERT INTO workflow_instance (campaign_id, customer_id, current_step_id) VALUES (?, ?, ?)",
			campaignId, customerId, stepId);
		long instanceId = jdbcTemplate.queryForObject("SELECT max(instance_id) FROM workflow_instance", Long.class);

		int first = sendQueueService.enqueueWorkflowStep(campaignId, instanceId, stepId, customerId, Channel.EMAIL);
		int second = sendQueueService.enqueueWorkflowStep(campaignId, instanceId, stepId, customerId, Channel.EMAIL);

		assertThat(first).isEqualTo(1);
		assertThat(second).isEqualTo(0);
	}
}
