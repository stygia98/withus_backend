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
 * 워크플로우 CONDITION 노드 (workflow-plan.md 3.2, 엔진 3/4) — 로컬 Docker DB, 테스트마다 롤백.
 * 구조: SEND_EMAIL(s1) → WAIT(w1) → CONDITION(c1, EMAIL_CLICKED)
 *       → yes: SEND_EMAIL(sendYes) → END   / no: SEND_EMAIL(sendNo) → END
 * END 는 current_step_id 를 바꾸지 않으므로(workflow-plan.md 3장) 분기 확인은 어느 SEND 가
 * 적재됐는지로 한다
 */
@SpringBootTest(properties = { "withus.scheduler.workflow-engine.enabled=false",
	"withus.scheduler.send-dispatcher.enabled=false" })
@Transactional
class WorkflowEngineConditionTest {

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
	long conditionStepId;
	long sendYesStepId;
	long sendNoStepId;

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

		long yesEndStepId = newStep("END", "{}", null);
		long noEndStepId = newStep("END", "{}", null);
		sendYesStepId = newStep("SEND_EMAIL", "{\"templateId\":111}", yesEndStepId);
		sendNoStepId = newStep("SEND_EMAIL", "{\"templateId\":222}", noEndStepId);
		conditionStepId = newConditionStep("{\"condition\":\"EMAIL_CLICKED\"}", sendYesStepId, sendNoStepId);
		waitStepId = newStep("WAIT", "{\"amount\":2,\"unit\":\"DAY\"}", conditionStepId);
		sendStepId = newStep("SEND_EMAIL", "{\"templateId\":999}", waitStepId);
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

	/** SEND→WAIT까지 실행해 send_log 를 만들고, WAIT 가 끝난 상황을 흉내 내 CONDITION 앞으로 옮긴다
	 * (wake() 는 트리거 3/3 범위라 아직 없어 여기서는 직접 되돌린다) */
	private WorkflowInstance sendThenMoveToCondition(long customerId) {
		WorkflowInstance instance = newInstance(customerId, sendStepId);
		workflowEngine.processOne(instance);
		jdbc.update("UPDATE workflow_instance SET status = 'RUNNING', current_step_id = ? WHERE instance_id = ?",
			conditionStepId, instance.getInstanceId());
		WorkflowInstance atCondition = new WorkflowInstance();
		atCondition.setInstanceId(instance.getInstanceId());
		atCondition.setCampaignId(campaignId);
		atCondition.setCustomerId(customerId);
		atCondition.setCurrentStepId(conditionStepId);
		return atCondition;
	}

	private long firstSendLogIdOf(long instanceId) {
		return jdbc.queryForObject("SELECT send_log_id FROM send_log WHERE instance_id = ? AND step_id = ?",
			Long.class, instanceId, sendStepId);
	}

	private long countSendLogAt(long instanceId, long stepId) {
		return jdbc.queryForObject("SELECT count(*) FROM send_log WHERE instance_id = ? AND step_id = ?", Long.class,
			instanceId, stepId);
	}

	@Test
	void 사람_클릭이_있으면_YES_경로로_간다() {
		long customerId = newCustomer("Y");
		WorkflowInstance atCondition = sendThenMoveToCondition(customerId);
		jdbc.update("INSERT INTO track_event (send_log_id, event_type, bot_yn) VALUES (?, 'CLICK', 'N')",
			firstSendLogIdOf(atCondition.getInstanceId()));

		workflowEngine.processOne(atCondition);

		assertThat(countSendLogAt(atCondition.getInstanceId(), sendYesStepId)).isEqualTo(1L);
		assertThat(countSendLogAt(atCondition.getInstanceId(), sendNoStepId)).isEqualTo(0L);
		assertCompleted(atCondition.getInstanceId());
	}

	@Test
	void 봇_클릭만_있으면_NO_경로로_간다() {
		long customerId = newCustomer("Y");
		WorkflowInstance atCondition = sendThenMoveToCondition(customerId);
		jdbc.update("INSERT INTO track_event (send_log_id, event_type, bot_yn) VALUES (?, 'CLICK', 'Y')",
			firstSendLogIdOf(atCondition.getInstanceId()));

		workflowEngine.processOne(atCondition);

		assertThat(countSendLogAt(atCondition.getInstanceId(), sendYesStepId)).isEqualTo(0L);
		assertThat(countSendLogAt(atCondition.getInstanceId(), sendNoStepId)).isEqualTo(1L);
		assertCompleted(atCondition.getInstanceId());
	}

	@Test
	void SKIPPED_발송_뒤는_NO_경로로_간다() {
		long customerId = newCustomer("N"); // 수신거부 — 적재 시점에 SKIPPED, track_event 가 있을 수 없다
		WorkflowInstance atCondition = sendThenMoveToCondition(customerId);

		workflowEngine.processOne(atCondition);

		assertThat(countSendLogAt(atCondition.getInstanceId(), sendYesStepId)).isEqualTo(0L);
		assertThat(countSendLogAt(atCondition.getInstanceId(), sendNoStepId)).isEqualTo(1L);
		assertCompleted(atCondition.getInstanceId());
	}

	@Test
	void 누적구매액_조건을_평가한다() {
		long endYes = newStep("END", "{}", null);
		long endNo = newStep("END", "{}", null);
		long sendOnYes = newStep("SEND_EMAIL", "{\"templateId\":333}", endYes);
		long sendOnNo = newStep("SEND_EMAIL", "{\"templateId\":444}", endNo);
		long purchaseCondition = newConditionStep("{\"condition\":\"PURCHASE_GTE\",\"amount\":100000}", sendOnYes,
			sendOnNo);
		long customerId = newCustomer("Y");
		jdbc.update("UPDATE customer SET total_purchase = 150000 WHERE customer_id = ?", customerId);
		WorkflowInstance instance = newInstance(customerId, purchaseCondition);

		workflowEngine.processOne(instance);

		assertThat(countSendLogAt(instance.getInstanceId(), sendOnYes)).isEqualTo(1L);
		assertThat(countSendLogAt(instance.getInstanceId(), sendOnNo)).isEqualTo(0L);
		assertCompleted(instance.getInstanceId());
	}

	private void assertCompleted(long instanceId) {
		String status = jdbc.queryForObject("SELECT status FROM workflow_instance WHERE instance_id = ?",
			String.class, instanceId);
		assertThat(status).isEqualTo("COMPLETED");
	}
}
