package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.withus.campaign.domain.SendStatus;
import com.withus.campaign.mapper.SendLogMapper;
import com.withus.common.domain.Channel;

/**
 * 발송 큐 배치 적재 (발송 큐 Plan 2장) — 유니크 충돌 시 멱등하게 건너뛰는지 검증
 * 로컬 Docker DB 를 쓰고 테스트마다 롤백한다
 */
@SpringBootTest
@Transactional
class SendLogMapperTest {

	@Autowired
	SendLogMapper sendLogMapper;
	@Autowired
	MemberMapper memberMapper;
	@Autowired
	JdbcTemplate jdbcTemplate;

	long memberId;
	long customerId;

	@BeforeEach
	void setUp() {
		Member member = new Member();
		member.setEmail("test-" + UUID.randomUUID() + "@withus.local");
		member.setPassword("x");
		member.setName("테스트");
		member.setRole(Role.MANAGER);
		memberMapper.insert(member);
		memberId = member.getMemberId();

		customerId = newCustomer();
	}

	private long newCustomer() {
		jdbcTemplate.update(
			"INSERT INTO customer (email, joined_at, source) VALUES (?, now(), 'MANUAL')",
			"customer-" + UUID.randomUUID() + "@withus.local");
		return jdbcTemplate.queryForObject("SELECT max(customer_id) FROM customer", Long.class);
	}

	private long newSegment() {
		jdbcTemplate.update("INSERT INTO segment (name, created_by) VALUES (?, ?)", "세그먼트", memberId);
		return jdbcTemplate.queryForObject("SELECT max(segment_id) FROM segment", Long.class);
	}

	private long newOneTimeCampaign(long segmentId) {
		jdbcTemplate.update(
			"INSERT INTO campaign (name, type, status, segment_id, created_by) VALUES (?, 'ONE_TIME', 'ACTIVE', ?, ?)",
			"캠페인", segmentId, memberId);
		return jdbcTemplate.queryForObject("SELECT max(campaign_id) FROM campaign", Long.class);
	}

	private long newWorkflowStep(long segmentId) {
		jdbcTemplate.update(
			"INSERT INTO campaign (name, type, status, segment_id, trigger_type, created_by) "
				+ "VALUES (?, 'WORKFLOW', 'ACTIVE', ?, 'CUSTOMER_REGISTERED', ?)",
			"워크플로우 캠페인", segmentId, memberId);
		long campaignId = jdbcTemplate.queryForObject("SELECT max(campaign_id) FROM campaign", Long.class);
		jdbcTemplate.update(
			"INSERT INTO workflow_step (campaign_id, node_type) VALUES (?, 'SEND_EMAIL')", campaignId);
		return jdbcTemplate.queryForObject("SELECT max(step_id) FROM workflow_step", Long.class);
	}

	private long newWorkflowInstance(long stepId, long customerId) {
		long campaignId = jdbcTemplate.queryForObject(
			"SELECT campaign_id FROM workflow_step WHERE step_id = ?", Long.class, stepId);
		jdbcTemplate.update(
			"INSERT INTO workflow_instance (campaign_id, customer_id, current_step_id) VALUES (?, ?, ?)",
			campaignId, customerId, stepId);
		return jdbcTemplate.queryForObject("SELECT max(instance_id) FROM workflow_instance", Long.class);
	}

	private SendLog oneTimeLog(long campaignId, long customerId) {
		return SendLog.builder()
			.campaignId(campaignId)
			.customerId(customerId)
			.recipient("customer@withus.local")
			.channel(Channel.EMAIL)
			.status(SendStatus.PENDING)
			.kind(SendKind.CAMPAIGN)
			.priority(SendLog.PRIORITY_CAMPAIGN_BULK)
			.build();
	}

	private SendLog workflowLog(long instanceId, long stepId, long customerId) {
		return SendLog.builder()
			.instanceId(instanceId)
			.stepId(stepId)
			.customerId(customerId)
			.recipient("customer@withus.local")
			.channel(Channel.EMAIL)
			.status(SendStatus.PENDING)
			.kind(SendKind.CAMPAIGN)
			.priority(SendLog.PRIORITY_WORKFLOW_OR_NOTICE)
			.build();
	}

	@Test
	void 같은_캠페인_고객을_두번_적재해도_한_건만_쌓인다() {
		long segmentId = newSegment();
		long campaignId = newOneTimeCampaign(segmentId);
		SendLog log = oneTimeLog(campaignId, customerId);

		int firstInserted = sendLogMapper.insertOneTimeBatch(List.of(log));
		int secondInserted = sendLogMapper.insertOneTimeBatch(List.of(log));

		assertThat(firstInserted).isEqualTo(1);
		assertThat(secondInserted).isEqualTo(0);
		long count = jdbcTemplate.queryForObject(
			"SELECT count(*) FROM send_log WHERE campaign_id = ? AND customer_id = ?",
			Long.class, campaignId, customerId);
		assertThat(count).isEqualTo(1);
	}

	@Test
	void 같은_인스턴스_스텝을_두번_적재해도_한_건만_쌓인다() {
		long segmentId = newSegment();
		long stepId = newWorkflowStep(segmentId);
		long instanceId = newWorkflowInstance(stepId, customerId);
		SendLog log = workflowLog(instanceId, stepId, customerId);

		int firstInserted = sendLogMapper.insertWorkflowBatch(List.of(log));
		int secondInserted = sendLogMapper.insertWorkflowBatch(List.of(log));

		assertThat(firstInserted).isEqualTo(1);
		assertThat(secondInserted).isEqualTo(0);
		long count = jdbcTemplate.queryForObject(
			"SELECT count(*) FROM send_log WHERE instance_id = ? AND step_id = ?",
			Long.class, instanceId, stepId);
		assertThat(count).isEqualTo(1);
	}

	@Test
	void 여러_건을_한번에_적재한다() {
		long segmentId = newSegment();
		long campaignId = newOneTimeCampaign(segmentId);
		long otherCustomerId = newCustomer();

		int inserted = sendLogMapper.insertOneTimeBatch(
			List.of(oneTimeLog(campaignId, customerId), oneTimeLog(campaignId, otherCustomerId)));

		assertThat(inserted).isEqualTo(2);
	}

	@Test
	void 선점은_우선순위가_높은_것부터_가져온다() {
		long segmentId = newSegment();
		long campaignId = newOneTimeCampaign(segmentId);
		long otherCustomerId = newCustomer();
		SendLog bulk = oneTimeLog(campaignId, customerId); // priority 3
		SendLog test = SendLog.builder()
			.campaignId(campaignId)
			.customerId(otherCustomerId)
			.recipient("customer@withus.local")
			.channel(Channel.EMAIL)
			.status(SendStatus.PENDING)
			.kind(SendKind.TEST)
			.priority(SendLog.PRIORITY_TEST) // priority 1
			.build();
		sendLogMapper.insertOneTimeBatch(List.of(bulk, test));

		List<SendLog> claimed = sendLogMapper.claimBatch();

		assertThat(claimed).extracting(SendLog::getCustomerId)
			.as("priority 1(TEST)이 priority 3(CAMPAIGN)보다 먼저 와야 한다")
			.containsExactly(otherCustomerId, customerId);
		assertThat(claimed).allSatisfy(log -> assertThat(log.getStatus()).isEqualTo(SendStatus.SENDING));
	}

	@Test
	void PAUSED_캠페인_건은_선점_대상에서_제외된다() {
		long segmentId = newSegment();
		long pausedCampaignId = newOneTimeCampaign(segmentId);
		jdbcTemplate.update("UPDATE campaign SET status = 'PAUSED' WHERE campaign_id = ?", pausedCampaignId);
		sendLogMapper.insertOneTimeBatch(List.of(oneTimeLog(pausedCampaignId, customerId)));

		long activeCampaignId = newOneTimeCampaign(segmentId);
		long otherCustomerId = newCustomer();
		sendLogMapper.insertOneTimeBatch(List.of(oneTimeLog(activeCampaignId, otherCustomerId)));

		List<SendLog> claimed = sendLogMapper.claimBatch();

		assertThat(claimed).extracting(SendLog::getCampaignId).containsOnly(activeCampaignId);
		String pausedStatus = jdbcTemplate.queryForObject(
			"SELECT status FROM send_log WHERE campaign_id = ?", String.class, pausedCampaignId);
		assertThat(pausedStatus).isEqualTo("PENDING");
	}

	@Test
	void 발송_성공을_기록하면_provider_message_id로_조회된다() {
		long segmentId = newSegment();
		long campaignId = newOneTimeCampaign(segmentId);
		sendLogMapper.insertOneTimeBatch(List.of(oneTimeLog(campaignId, customerId)));
		long sendLogId = jdbcTemplate.queryForObject(
			"SELECT send_log_id FROM send_log WHERE campaign_id = ? AND customer_id = ?",
			Long.class, campaignId, customerId);

		sendLogMapper.recordSent(sendLogId, "ses-message-id-1");

		SendLog found = sendLogMapper.findByProviderMessageId("ses-message-id-1");
		assertThat(found.getSendLogId()).isEqualTo(sendLogId);
		assertThat(found.getStatus()).isEqualTo(SendStatus.SENT);
		assertThat(found.getSentAt()).isNotNull();
		assertThat(found.getCustomerId()).isEqualTo(customerId);
	}

	@Test
	void 발송_실패를_기록하면_오류_메시지가_남는다() {
		long segmentId = newSegment();
		long campaignId = newOneTimeCampaign(segmentId);
		sendLogMapper.insertOneTimeBatch(List.of(oneTimeLog(campaignId, customerId)));
		long sendLogId = jdbcTemplate.queryForObject(
			"SELECT send_log_id FROM send_log WHERE campaign_id = ? AND customer_id = ?",
			Long.class, campaignId, customerId);

		sendLogMapper.recordFailed(sendLogId, "PERMANENT");

		String status = jdbcTemplate.queryForObject(
			"SELECT status FROM send_log WHERE send_log_id = ?", String.class, sendLogId);
		String errorMessage = jdbcTemplate.queryForObject(
			"SELECT error_message FROM send_log WHERE send_log_id = ?", String.class, sendLogId);
		assertThat(status).isEqualTo("FAILED");
		assertThat(errorMessage).isEqualTo("PERMANENT");
	}
}
