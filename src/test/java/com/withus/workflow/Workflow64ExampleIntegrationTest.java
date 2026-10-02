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
 * PRD 6.4 예시 워크플로우 통합 테스트 (엔진 4/4, PRD 10.3) — 로컬 Docker DB, 테스트마다 롤백.
 *
 * <pre>
 * TRIGGER(CUSTOMER_REGISTERED)
 *  └ SEND_EMAIL(환영 메일)
 *     └ WAIT(2일)
 *        └ CONDITION(EMAIL_CLICKED)
 *           ├ YES → CONDITION(PURCHASE_GTE 100000)
 *           │        ├ YES → SEND_EMAIL(VIP 쿠폰) → END
 *           │        └ NO  → SEND_EMAIL(일반 쿠폰) → END
 *           └ NO  → SEND_SMS(리마인드) → END
 * </pre>
 *
 * wake()(직전 SEND 발송 완료로 WAIT를 깨우는 동작)는 트리거 3/3 범위라 아직 없다 — WAIT가 끝난 상황은
 * 테스트에서 직접 current_step_id를 옮겨 흉내 낸다.
 */
@SpringBootTest(properties = { "withus.scheduler.workflow-engine.enabled=false",
	"withus.scheduler.send-dispatcher.enabled=false", "withus.scheduler.workflow-recovery.enabled=false" })
@Transactional
class Workflow64ExampleIntegrationTest {

	@Autowired
	WorkflowEngine workflowEngine;
	@Autowired
	MemberMapper memberMapper;
	@Autowired
	JdbcTemplate jdbc;

	long memberId;
	long segmentId;
	long campaignId;
	long sendWelcomeStepId;
	long conditionClickStepId;
	long sendVipStepId;
	long sendNormalStepId;
	long sendSmsStepId;

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
			VALUES ('6.4 예시', 'WORKFLOW', 'ACTIVE', ?, 'CUSTOMER_REGISTERED', ?) RETURNING campaign_id
			""", Long.class, segmentId, memberId);

		long endVip = newStep("END", "{}", null);
		long endNormal = newStep("END", "{}", null);
		long endSms = newStep("END", "{}", null);
		sendVipStepId = newStep("SEND_EMAIL", "{\"templateId\":201}", endVip);
		sendNormalStepId = newStep("SEND_EMAIL", "{\"templateId\":202}", endNormal);
		sendSmsStepId = newStep("SEND_SMS", "{\"templateId\":203}", endSms);
		long conditionPurchase = newConditionStep("{\"condition\":\"PURCHASE_GTE\",\"amount\":100000}", sendVipStepId,
			sendNormalStepId);
		conditionClickStepId = newConditionStep("{\"condition\":\"EMAIL_CLICKED\"}", conditionPurchase,
			sendSmsStepId);
		long waitStepId = newStep("WAIT", "{\"amount\":2,\"unit\":\"DAY\"}", conditionClickStepId);
		sendWelcomeStepId = newStep("SEND_EMAIL", "{\"templateId\":100}", waitStepId);
		newStep("TRIGGER", "{\"triggerType\":\"CUSTOMER_REGISTERED\"}", sendWelcomeStepId);
	}

	private long newStep(String nodeType, String configJson, Long nextStepId) {
		return jdbc.queryForObject(
			"INSERT INTO workflow_step (campaign_id, node_type, config_json, next_step_id) VALUES (?, ?, ?::jsonb, ?) "
				+ "RETURNING step_id",
			Long.class, campaignId, nodeType, configJson, nextStepId);
	}

	private long newConditionStep(String configJson, long yesStepId, long noStepId) {
		return jdbc.queryForObject(
			"INSERT INTO workflow_step (campaign_id, node_type, config_json, yes_step_id, no_step_id) "
				+ "VALUES (?, 'CONDITION', ?::jsonb, ?, ?) RETURNING step_id",
			Long.class, campaignId, configJson, yesStepId, noStepId);
	}

	private long newCustomer() {
		return jdbc.queryForObject("""
			INSERT INTO customer (email, joined_at, source, email_consent_yn) VALUES (?, now(), 'MANUAL', 'Y')
			RETURNING customer_id
			""", Long.class, "customer-" + UUID.randomUUID() + "@withus.local");
	}

	private long countSendLogAt(long instanceId, long stepId) {
		return jdbc.queryForObject("SELECT count(*) FROM send_log WHERE instance_id = ? AND step_id = ?", Long.class,
			instanceId, stepId);
	}

	@Test
	void 클릭한_고객은_구매액에_따라_VIP_또는_일반_쿠폰_메일을_받는다() {
		long customerId = newCustomer();
		jdbc.update("UPDATE customer SET total_purchase = 150000 WHERE customer_id = ?", customerId);
		long instanceId = jdbc.queryForObject("""
			INSERT INTO workflow_instance (campaign_id, customer_id, current_step_id, status, next_run_at)
			VALUES (?, ?, ?, 'RUNNING', NULL) RETURNING instance_id
			""", Long.class, campaignId, customerId, sendWelcomeStepId);
		WorkflowInstance instance = instanceOf(instanceId, customerId, sendWelcomeStepId);

		workflowEngine.processOne(instance); // 환영 메일 적재 → WAIT 대기
		long welcomeSendLogId = jdbc.queryForObject(
			"SELECT send_log_id FROM send_log WHERE instance_id = ? AND step_id = ?", Long.class, instanceId,
			sendWelcomeStepId);
		jdbc.update("INSERT INTO track_event (send_log_id, event_type, bot_yn) VALUES (?, 'CLICK', 'N')",
			welcomeSendLogId);
		moveToStep(instanceId, conditionClickStepId); // WAIT 가 끝난 상황을 흉내 낸다(wake() 는 트리거 3/3 범위)

		workflowEngine.processOne(instanceOf(instanceId, customerId, conditionClickStepId));

		assertThat(countSendLogAt(instanceId, sendVipStepId)).as("클릭 + 누적구매액 100000 이상 → VIP 쿠폰").isEqualTo(1L);
		assertThat(countSendLogAt(instanceId, sendNormalStepId)).isEqualTo(0L);
		assertThat(countSendLogAt(instanceId, sendSmsStepId)).isEqualTo(0L);
		assertThat(statusOf(instanceId)).isEqualTo("COMPLETED");
	}

	@Test
	void 미클릭_고객은_SMS_리마인드를_받는다() {
		long customerId = newCustomer();
		long instanceId = jdbc.queryForObject("""
			INSERT INTO workflow_instance (campaign_id, customer_id, current_step_id, status, next_run_at)
			VALUES (?, ?, ?, 'RUNNING', NULL) RETURNING instance_id
			""", Long.class, campaignId, customerId, sendWelcomeStepId);
		WorkflowInstance instance = instanceOf(instanceId, customerId, sendWelcomeStepId);

		workflowEngine.processOne(instance); // 환영 메일 적재 → WAIT 대기, 클릭 없음
		moveToStep(instanceId, conditionClickStepId);

		workflowEngine.processOne(instanceOf(instanceId, customerId, conditionClickStepId));

		assertThat(countSendLogAt(instanceId, sendSmsStepId)).as("미클릭 → SMS 리마인드").isEqualTo(1L);
		assertThat(countSendLogAt(instanceId, sendVipStepId)).isEqualTo(0L);
		assertThat(countSendLogAt(instanceId, sendNormalStepId)).isEqualTo(0L);
		assertThat(statusOf(instanceId)).isEqualTo("COMPLETED");
	}

	private void moveToStep(long instanceId, long stepId) {
		jdbc.update("UPDATE workflow_instance SET status = 'RUNNING', current_step_id = ? WHERE instance_id = ?",
			stepId, instanceId);
	}

	private String statusOf(long instanceId) {
		return jdbc.queryForObject("SELECT status FROM workflow_instance WHERE instance_id = ?", String.class,
			instanceId);
	}

	private WorkflowInstance instanceOf(long instanceId, long customerId, long currentStepId) {
		WorkflowInstance instance = new WorkflowInstance();
		instance.setInstanceId(instanceId);
		instance.setCampaignId(campaignId);
		instance.setCustomerId(customerId);
		instance.setCurrentStepId(currentStepId);
		return instance;
	}
}
