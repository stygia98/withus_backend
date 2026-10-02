package com.withus.workflow;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.withus.workflow.domain.WorkflowInstance;
import com.withus.workflow.service.WorkflowEngine;

/**
 * 워크플로우 노드 실행기 (workflow-plan.md 3장, 엔진 2/4) — 로컬 Docker DB, 테스트마다 롤백.
 * send-dispatcher·workflow-engine 백그라운드 스케줄러를 둘 다 꺼서 processOne() 결과를 직접 검증한다
 */
@SpringBootTest(properties = { "withus.scheduler.workflow-engine.enabled=false",
	"withus.scheduler.send-dispatcher.enabled=false" })
@Transactional
class WorkflowEngineTest {

	@Autowired
	WorkflowEngine workflowEngine;
	@Autowired
	MemberMapper memberMapper;
	@Autowired
	JdbcTemplate jdbc;

	long memberId;
	long segmentId;
	long campaignId;
	long sendStepId;
	long waitStepId;
	long endStepId;

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
		campaignId = jdbc.queryForObject("""
			INSERT INTO campaign (name, type, status, segment_id, trigger_type, created_by)
			VALUES ('여정', 'WORKFLOW', 'ACTIVE', ?, 'CUSTOMER_REGISTERED', ?) RETURNING campaign_id
			""", Long.class, segmentId, memberId);

		endStepId = jdbc.queryForObject("""
			INSERT INTO workflow_step (campaign_id, node_type, config_json) VALUES (?, 'END', '{}'::jsonb)
			RETURNING step_id
			""", Long.class, campaignId);
		waitStepId = jdbc.queryForObject("""
			INSERT INTO workflow_step (campaign_id, node_type, config_json, next_step_id)
			VALUES (?, 'WAIT', '{"amount":2,"unit":"DAY"}'::jsonb, ?) RETURNING step_id
			""", Long.class, campaignId, endStepId);
		sendStepId = jdbc.queryForObject("""
			INSERT INTO workflow_step (campaign_id, node_type, config_json, next_step_id)
			VALUES (?, 'SEND_EMAIL', '{"templateId":999}'::jsonb, ?) RETURNING step_id
			""", Long.class, campaignId, waitStepId);
	}

	private long newCustomer(String consentYn) {
		return jdbc.queryForObject("""
			INSERT INTO customer (email, joined_at, source, email_consent_yn) VALUES (?, now(), 'MANUAL', ?)
			RETURNING customer_id
			""", Long.class, "customer-" + UUID.randomUUID() + "@withus.local", consentYn);
	}

	private WorkflowInstance newInstance(long customerId, long currentStepId) {
		long instanceId = jdbc.queryForObject("""
			INSERT INTO workflow_instance (campaign_id, customer_id, current_step_id, status, next_run_at)
			VALUES (?, ?, ?, 'RUNNING', NULL) RETURNING instance_id
			""", Long.class, campaignId, customerId, currentStepId);
		WorkflowInstance instance = new WorkflowInstance();
		instance.setInstanceId(instanceId);
		instance.setCampaignId(campaignId);
		instance.setCustomerId(customerId);
		instance.setCurrentStepId(currentStepId);
		return instance;
	}

	@Test
	void SEND_직후_WAIT는_next_run_at을_비워_발송_결과를_기다린다() {
		long customerId = newCustomer("Y");
		WorkflowInstance instance = newInstance(customerId, sendStepId);

		workflowEngine.processOne(instance);

		Long sendLogCount = jdbc.queryForObject(
			"SELECT count(*) FROM send_log WHERE instance_id = ? AND step_id = ?", Long.class,
			instance.getInstanceId(), sendStepId);
		assertThat(sendLogCount).isEqualTo(1L);
		String status = jdbc.queryForObject("SELECT status FROM workflow_instance WHERE instance_id = ?",
			String.class, instance.getInstanceId());
		Long currentStepId = jdbc.queryForObject(
			"SELECT current_step_id FROM workflow_instance WHERE instance_id = ?", Long.class,
			instance.getInstanceId());
		Boolean nextRunAtIsNull = jdbc.queryForObject(
			"SELECT next_run_at IS NULL FROM workflow_instance WHERE instance_id = ?", Boolean.class,
			instance.getInstanceId());
		assertThat(status).isEqualTo("WAITING");
		assertThat(currentStepId).isEqualTo(endStepId);
		assertThat(nextRunAtIsNull).isTrue();
	}

	@Test
	void 적재_시점에_수신거부면_WAIT에서_즉시_대기_시각을_계산한다() {
		long customerId = newCustomer("N");
		WorkflowInstance instance = newInstance(customerId, sendStepId);

		workflowEngine.processOne(instance);

		Boolean nextRunAtIsNull = jdbc.queryForObject(
			"SELECT next_run_at IS NULL FROM workflow_instance WHERE instance_id = ?", Boolean.class,
			instance.getInstanceId());
		assertThat(nextRunAtIsNull).as("SKIPPED 로 적재된 건은 wake() 가 오지 않으므로 즉시 계산해야 한다(PL 리뷰 R2)")
			.isFalse();
	}

	@Test
	void 같은_인스턴스를_두_번_실행해도_send_log는_1건이다() {
		long customerId = newCustomer("Y");
		WorkflowInstance instance = newInstance(customerId, sendStepId);

		workflowEngine.processOne(instance);
		workflowEngine.processOne(instance); // 같은 currentStepId(sendStepId)로 재실행 — 멈춤 복구 뒤 재시도를 흉내낸다

		Long sendLogCount = jdbc.queryForObject(
			"SELECT count(*) FROM send_log WHERE instance_id = ? AND step_id = ?", Long.class,
			instance.getInstanceId(), sendStepId);
		assertThat(sendLogCount).isEqualTo(1L);
	}
}
