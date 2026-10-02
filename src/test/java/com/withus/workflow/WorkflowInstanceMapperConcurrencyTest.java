package com.withus.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
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
import com.withus.workflow.domain.WorkflowInstance;
import com.withus.workflow.mapper.WorkflowInstanceMapper;

/**
 * 선점 동시성 (DB_SCHEMA 7장, 엔진 1/4) — FOR UPDATE SKIP LOCKED 가 실제로 중복 선점을 막는지 검증.
 * @Transactional 을 안 쓴다: 두 스레드가 서로 다른 커넥션으로 동시에 들어가야 하므로 직접 정리한다
 */
@SpringBootTest(properties = "withus.scheduler.workflow-engine.enabled=false")
class WorkflowInstanceMapperConcurrencyTest {

	@Autowired
	WorkflowInstanceMapper workflowInstanceMapper;
	@Autowired
	MemberMapper memberMapper;
	@Autowired
	JdbcTemplate jdbc;

	Long campaignId;
	Long segmentId;
	Long memberId;
	final List<Long> customerIds = new ArrayList<>();

	@AfterEach
	void cleanUp() {
		if (campaignId != null) {
			jdbc.update("DELETE FROM workflow_instance WHERE campaign_id = ?", campaignId);
			jdbc.update("DELETE FROM workflow_step WHERE campaign_id = ?", campaignId);
			jdbc.update("DELETE FROM campaign WHERE campaign_id = ?", campaignId);
		}
		if (segmentId != null) {
			jdbc.update("DELETE FROM segment WHERE segment_id = ?", segmentId);
		}
		for (Long customerId : customerIds) {
			jdbc.update("DELETE FROM customer WHERE customer_id = ?", customerId);
		}
		if (memberId != null) {
			jdbc.update("DELETE FROM member WHERE member_id = ?", memberId);
		}
	}

	@Test
	void 두_스레드가_동시에_선점해도_같은_인스턴스를_두번_잡지_않는다() throws Exception {
		Member member = new Member();
		member.setEmail("test-" + UUID.randomUUID() + "@withus.local");
		member.setPassword("x");
		member.setName("테스트");
		member.setRole(Role.MANAGER);
		memberMapper.insert(member);
		memberId = member.getMemberId();

		segmentId = jdbc.queryForObject(
			"INSERT INTO segment (name, created_by) VALUES ('세그먼트', ?) RETURNING segment_id", Long.class, memberId);
		campaignId = jdbc.queryForObject("""
			INSERT INTO campaign (name, type, status, segment_id, trigger_type, created_by)
			VALUES ('여정', 'WORKFLOW', 'ACTIVE', ?, 'CUSTOMER_REGISTERED', ?) RETURNING campaign_id
			""", Long.class, segmentId, memberId);
		long stepId = jdbc.queryForObject("""
			INSERT INTO workflow_step (campaign_id, node_type, config_json) VALUES (?, 'TRIGGER', '{}'::jsonb)
			RETURNING step_id
			""", Long.class, campaignId);

		for (int i = 0; i < 80; i++) {
			long customerId = jdbc.queryForObject(
				"INSERT INTO customer (email, joined_at, source) VALUES (?, now(), 'MANUAL') RETURNING customer_id",
				Long.class, "customer-" + UUID.randomUUID() + "@withus.local");
			customerIds.add(customerId);
			jdbc.update("""
				INSERT INTO workflow_instance (campaign_id, customer_id, current_step_id, status, next_run_at)
				VALUES (?, ?, ?, 'WAITING', ?)
				""", campaignId, customerId, stepId, OffsetDateTime.now().minusSeconds(1));
		}

		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<List<WorkflowInstance>> first = executor.submit(workflowInstanceMapper::claimBatch);
			Future<List<WorkflowInstance>> second = executor.submit(workflowInstanceMapper::claimBatch);

			List<Long> firstIds = idsOf(first.get());
			List<Long> secondIds = idsOf(second.get());

			assertThat(firstIds).as("두 스레드가 겹치는 행이 없어야 한다").doesNotContainAnyElementsOf(secondIds);
			assertThat(firstIds.size() + secondIds.size()).isEqualTo(80);
			Long runningCount = jdbc.queryForObject(
				"SELECT count(*) FROM workflow_instance WHERE campaign_id = ? AND status = 'RUNNING'", Long.class,
				campaignId);
			assertThat(runningCount).isEqualTo(80L);
		} finally {
			executor.shutdown();
		}
	}

	private static List<Long> idsOf(List<WorkflowInstance> instances) {
		return instances.stream().map(WorkflowInstance::getInstanceId).collect(Collectors.toList());
	}
}
