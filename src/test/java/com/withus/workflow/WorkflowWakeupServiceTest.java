package com.withus.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.withus.campaign.domain.SendLog;
import com.withus.campaign.domain.SendStatus;
import com.withus.campaign.mapper.SendLogMapper;
import com.withus.workflow.domain.NodeType;
import com.withus.workflow.domain.WorkflowInstance;
import com.withus.workflow.domain.WorkflowStep;
import com.withus.workflow.mapper.WorkflowInstanceMapper;
import com.withus.workflow.mapper.WorkflowStepMapper;
import com.withus.workflow.service.WorkflowWakeupService;

import tools.jackson.databind.json.JsonMapper;

/** 발송 결과로 인스턴스 깨우기 (워크플로우 Plan 4.2, R1) — DB 없이 실행된다 */
class WorkflowWakeupServiceTest {

	static final long SEND_STEP = 1, WAIT_STEP = 2, AFTER_WAIT = 3, INSTANCE = 9, SEND_LOG = 100;

	SendLogMapper sendLogMapper = mock(SendLogMapper.class);
	WorkflowStepMapper stepMapper = mock(WorkflowStepMapper.class);
	WorkflowInstanceMapper instanceMapper = mock(WorkflowInstanceMapper.class);
	WorkflowWakeupService service = new WorkflowWakeupService(sendLogMapper, stepMapper, instanceMapper,
		JsonMapper.builder().build());
	SendLog claimed = SendLog.builder().sendLogId(SEND_LOG).instanceId(INSTANCE).stepId(SEND_STEP).build();

	private WorkflowStep step(NodeType type, Long next, String config) {
		WorkflowStep step = new WorkflowStep();
		step.setNodeType(type);
		step.setNextStepId(next);
		step.setConfigJson(config);
		return step;
	}

	private void instanceAt(long currentStepId) {
		WorkflowInstance instance = new WorkflowInstance();
		instance.setInstanceId(INSTANCE);
		instance.setCurrentStepId(currentStepId);
		when(instanceMapper.findById(INSTANCE)).thenReturn(instance);
	}

	private void result(SendStatus status, OffsetDateTime sentAt) {
		when(sendLogMapper.findResultById(SEND_LOG))
			.thenReturn(SendLog.builder().sendLogId(SEND_LOG).status(status).sentAt(sentAt).build());
	}

	@BeforeEach
	void setUp() {
		when(stepMapper.findById(SEND_STEP)).thenReturn(step(NodeType.SEND_EMAIL, WAIT_STEP, "{}"));
		when(stepMapper.findById(WAIT_STEP))
			.thenReturn(step(NodeType.WAIT, AFTER_WAIT, "{\"amount\":2,\"unit\":\"DAY\"}"));
	}

	@Test
	void SENT이면_실제_발송_시각_sent_at에_대기시간을_더한다() {
		instanceAt(AFTER_WAIT);
		OffsetDateTime sentAt = OffsetDateTime.parse("2026-10-05T08:00:00+09:00"); // 야간 보류 뒤 실제 발송
		result(SendStatus.SENT, sentAt);

		service.wake(claimed);

		verify(instanceMapper).wake(INSTANCE, sentAt.plusDays(2));
	}

	@Test
	void SKIPPED이면_지금부터_센다() {
		instanceAt(AFTER_WAIT);
		result(SendStatus.SKIPPED, null);

		service.wake(claimed);

		ArgumentCaptor<OffsetDateTime> at = ArgumentCaptor.forClass(OffsetDateTime.class);
		verify(instanceMapper).wake(org.mockito.ArgumentMatchers.eq(INSTANCE), at.capture());
		assertThat(at.getValue()).isBetween(OffsetDateTime.now().plusDays(2).minusMinutes(1),
			OffsetDateTime.now().plusDays(2).plusMinutes(1));
	}

	@Test
	void R1_인스턴스가_이_WAIT를_기다리는_중이_아니면_깨우지_않는다() {
		instanceAt(77L); // SEND_A→CONDITION→SEND_B→WAIT 에서 A 의 결과가 먼저 온 경우: 아직 다른 노드 앞
		result(SendStatus.SENT, OffsetDateTime.now());

		service.wake(claimed);

		verify(instanceMapper, never()).wake(anyLong(), any());
	}

	@Test
	void SEND_바로_뒤가_WAIT가_아니면_깨우지_않는다() {
		when(stepMapper.findById(SEND_STEP)).thenReturn(step(NodeType.SEND_EMAIL, 5L, "{}"));
		when(stepMapper.findById(5L)).thenReturn(step(NodeType.CONDITION, null, "{}"));

		service.wake(claimed);

		verify(instanceMapper, never()).wake(anyLong(), any());
	}

	@Test
	void 워크플로우_발송_건이_아니면_아무_일도_없다() {
		service.wake(SendLog.builder().sendLogId(1L).build());

		verify(stepMapper, never()).findById(anyLong());
	}
}
