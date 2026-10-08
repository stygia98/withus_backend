package com.withus.workflow.service;

import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.springframework.stereotype.Service;

import com.withus.campaign.domain.SendLog;
import com.withus.campaign.domain.SendStatus;
import com.withus.campaign.mapper.SendLogMapper;
import com.withus.workflow.domain.NodeType;
import com.withus.workflow.domain.WorkflowInstance;
import com.withus.workflow.domain.WorkflowStep;
import com.withus.workflow.mapper.WorkflowInstanceMapper;
import com.withus.workflow.mapper.WorkflowStepMapper;

import tools.jackson.databind.ObjectMapper;

/** 워크플로우 Plan 4.2 (PL 리뷰 R1 포함) */
@Service
public class WorkflowWakeupService implements WorkflowWakeup {

	private final SendLogMapper sendLogMapper;
	private final WorkflowStepMapper workflowStepMapper;
	private final WorkflowInstanceMapper workflowInstanceMapper;
	private final ObjectMapper objectMapper;

	public WorkflowWakeupService(SendLogMapper sendLogMapper, WorkflowStepMapper workflowStepMapper,
			WorkflowInstanceMapper workflowInstanceMapper, ObjectMapper objectMapper) {
		this.sendLogMapper = sendLogMapper;
		this.workflowStepMapper = workflowStepMapper;
		this.workflowInstanceMapper = workflowInstanceMapper;
		this.objectMapper = objectMapper;
	}

	@Override
	public void wake(SendLog sendLog) {
		if (sendLog.getInstanceId() == null) {
			return;
		}
		WorkflowStep sendStep = workflowStepMapper.findById(sendLog.getStepId());
		WorkflowStep nextStep = sendStep.getNextStepId() == null ? null
			: workflowStepMapper.findById(sendStep.getNextStepId());
		if (nextStep == null || nextStep.getNodeType() != NodeType.WAIT) {
			return; // 바로 뒤가 WAIT 가 아니면 이 SEND 는 블로킹 지점이 아니다
		}
		WorkflowInstance instance = workflowInstanceMapper.findById(sendLog.getInstanceId());
		// R1: 인스턴스가 지금 정말 이 WAIT 를 기다리는 중일 때만 깨운다(WAIT 의 다음 노드 == current_step_id)
		if (instance == null || !nextStep.getNextStepId().equals(instance.getCurrentStepId())) {
			return;
		}
		// 결과 기록 직후이므로 최신 status·sent_at 은 DB 에서 다시 읽는다(claim 시점의 sendLog 는 낡았다)
		SendLog latest = sendLogMapper.findResultById(sendLog.getSendLogId());
		OffsetDateTime base = latest.getStatus() == SendStatus.SENT && latest.getSentAt() != null ? latest.getSentAt()
			: OffsetDateTime.now(ZoneId.of("Asia/Seoul")); // SKIPPED·FAILED 는 now (PRD 6.5-4)
		workflowInstanceMapper.wake(instance.getInstanceId(),
			base.plus(WaitDurations.of(nextStep.getConfigJson(), objectMapper)));
	}
}
