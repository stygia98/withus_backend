package com.withus.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
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
import com.withus.workflow.domain.InstanceStatus;
import com.withus.workflow.domain.WorkflowInstance;
import com.withus.workflow.mapper.WorkflowInstanceMapper;

/**
 * 워크플로우 인스턴스 선점 (DB_SCHEMA 7장, 엔진 1/4) — 로컬 Docker DB, 테스트마다 롤백
 */
@SpringBootTest(properties = { "withus.scheduler.workflow-engine.enabled=false",
	"withus.scheduler.workflow-recovery.enabled=false" })
@Transactional
class WorkflowInstanceMapperTest {

	@Autowired
	WorkflowInstanceMapper workflowInstanceMapper;
	@Autowired
	MemberMapper memberMapper;
	@Autowired
	JdbcTemplate jdbc;

	long memberId;
	long segmentId;
	long stepId;

	@BeforeEach
	void setUp() {
		Member member = new Member();
		member.setEmail("test-" + UUID.randomUUID() + "@withus.local");
		member.setPassword("x");
		member.setName("테스트");
		member.setRole(Role.MANAGER);
		memberMapper.insert(member);
		memberId = member.getMemberId();

		segmentId = jdbc.queryForObject(
			"INSERT INTO segment (name, created_by) VALUES ('세그먼트', ?) RETURNING segment_id", Long.class, memberId);
	}

	private long newCampaign(String status) {
		return jdbc.queryForObject("""
			INSERT INTO campaign (name, type, status, segment_id, trigger_type, created_by)
			VALUES ('여정', 'WORKFLOW', ?, ?, 'CUSTOMER_REGISTERED', ?) RETURNING campaign_id
			""", Long.class, status, segmentId, memberId);
	}

	private long newStep(long campaignId) {
		return jdbc.queryForObject("""
			INSERT INTO workflow_step (campaign_id, node_type, config_json) VALUES (?, 'TRIGGER', '{}'::jsonb)
			RETURNING step_id
			""", Long.class, campaignId);
	}

	private long newCustomer() {
		return jdbc.queryForObject("""
			INSERT INTO customer (email, joined_at, source) VALUES (?, now(), 'MANUAL') RETURNING customer_id
			""", Long.class, "customer-" + UUID.randomUUID() + "@withus.local");
	}

	private long newInstance(long campaignId, long stepId, OffsetDateTime nextRunAt) {
		return jdbc.queryForObject("""
			INSERT INTO workflow_instance (campaign_id, customer_id, current_step_id, status, next_run_at)
			VALUES (?, ?, ?, 'WAITING', ?) RETURNING instance_id
			""", Long.class, campaignId, newCustomer(), stepId, nextRunAt);
	}

	@Test
	void 실행_시각이_지난_WAITING_인스턴스를_선점해_RUNNING으로_바꾼다() {
		long campaignId = newCampaign("ACTIVE");
		stepId = newStep(campaignId);
		long due = newInstance(campaignId, stepId, OffsetDateTime.now().minusMinutes(1));
		long notYet = newInstance(campaignId, stepId, OffsetDateTime.now().plusMinutes(10));

		List<WorkflowInstance> claimed = workflowInstanceMapper.claimBatch();

		assertThat(claimed).extracting(WorkflowInstance::getInstanceId).containsExactly(due);
		assertThat(claimed.get(0).getStatus()).isEqualTo(InstanceStatus.RUNNING);
		String notYetStatus = jdbc.queryForObject("SELECT status FROM workflow_instance WHERE instance_id = ?",
			String.class, notYet);
		assertThat(notYetStatus).isEqualTo("WAITING");
	}

	@Test
	void PAUSED_캠페인의_인스턴스는_선점하지_않는다() {
		long campaignId = newCampaign("PAUSED");
		stepId = newStep(campaignId);
		long instanceId = newInstance(campaignId, stepId, OffsetDateTime.now().minusMinutes(1));

		List<WorkflowInstance> claimed = workflowInstanceMapper.claimBatch();

		assertThat(claimed).extracting(WorkflowInstance::getInstanceId).doesNotContain(instanceId);
		String status = jdbc.queryForObject("SELECT status FROM workflow_instance WHERE instance_id = ?",
			String.class, instanceId);
		assertThat(status).isEqualTo("WAITING");
	}

	@Test
	void 오백한_건이면_두_번에_나눠_전부_선점한다() {
		long campaignId = newCampaign("ACTIVE");
		stepId = newStep(campaignId);
		for (int i = 0; i < 501; i++) {
			newInstance(campaignId, stepId, OffsetDateTime.now().minusSeconds(1));
		}

		List<WorkflowInstance> firstBatch = workflowInstanceMapper.claimBatch();
		List<WorkflowInstance> secondBatch = workflowInstanceMapper.claimBatch();
		List<WorkflowInstance> thirdBatch = workflowInstanceMapper.claimBatch();

		assertThat(firstBatch).hasSize(500);
		assertThat(secondBatch).hasSize(1);
		assertThat(thirdBatch).isEmpty();
	}

	@Test
	void RUNNING으로_10분_넘게_남은_인스턴스는_WAITING으로_복구된다() {
		long campaignId = newCampaign("ACTIVE");
		stepId = newStep(campaignId);
		long stuckId = newInstance(campaignId, stepId, OffsetDateTime.now());
		jdbc.update("UPDATE workflow_instance SET status = 'RUNNING', updated_at = now() - INTERVAL '11 minutes' "
			+ "WHERE instance_id = ?", stuckId);
		long freshId = newInstance(campaignId, stepId, OffsetDateTime.now());
		jdbc.update("UPDATE workflow_instance SET status = 'RUNNING', updated_at = now() - INTERVAL '5 minutes' "
			+ "WHERE instance_id = ?", freshId);

		int recovered = workflowInstanceMapper.recoverStuckRunning();

		assertThat(recovered).isEqualTo(1);
		String stuckStatus = jdbc.queryForObject("SELECT status FROM workflow_instance WHERE instance_id = ?",
			String.class, stuckId);
		String freshStatus = jdbc.queryForObject("SELECT status FROM workflow_instance WHERE instance_id = ?",
			String.class, freshId);
		assertThat(stuckStatus).isEqualTo("WAITING");
		assertThat(freshStatus).isEqualTo("RUNNING");
	}

	@Test
	void insertBatch는_재실행해도_중복_생성하지_않고_삭제된_고객은_제외한다() {
		long campaignId = newCampaign("ACTIVE");
		stepId = newStep(campaignId);
		long a = newCustomer();
		long b = newCustomer();
		long deleted = newCustomer();
		jdbc.update("UPDATE customer SET deleted_yn = 'Y' WHERE customer_id = ?", deleted);

		int first = workflowInstanceMapper.insertBatch(campaignId, stepId, List.of(a, b, deleted));
		int second = workflowInstanceMapper.insertBatch(campaignId, stepId, List.of(a, b, deleted));

		assertThat(first).isEqualTo(2);
		assertThat(second).isZero();
	}
}
