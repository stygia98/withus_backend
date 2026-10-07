package com.withus.workflow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


import java.util.HashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import com.withus.campaign.domain.SendStatus;
import com.withus.campaign.mapper.SendLogMapper;
import com.withus.campaign.service.SendQueueService;
import com.withus.common.domain.Channel;
import com.withus.tracking.service.TrackEventRepository;
import com.withus.workflow.domain.NodeType;
import com.withus.workflow.domain.WorkflowInstance;
import com.withus.workflow.domain.WorkflowStep;
import com.withus.workflow.mapper.WorkflowInstanceMapper;
import com.withus.workflow.mapper.WorkflowStepMapper;
import com.withus.workflow.service.WorkflowEngine;

import tools.jackson.databind.json.JsonMapper;

/** 엔진의 WAIT 처리·재시도 규칙 (PR #35 리뷰 🔴2, 🟡3·4) — DB 없이 실행된다 */
class WorkflowEngineUnitTest {

	WorkflowStepMapper stepMapper = mock(WorkflowStepMapper.class);
	WorkflowInstanceMapper instanceMapper = mock(WorkflowInstanceMapper.class);
	SendLogMapper sendLogMapper = mock(SendLogMapper.class);
	TrackEventRepository trackEvents = mock(TrackEventRepository.class);
	WorkflowEngine engine = new WorkflowEngine(stepMapper, instanceMapper, mock(SendQueueService.class), sendLogMapper,
		trackEvents, JsonMapper.builder().build(), mock(PlatformTransactionManager.class));

	private WorkflowStep step(long id, NodeType type, String config, Long next, Long yes, Long no) {
		WorkflowStep step = new WorkflowStep();
		step.setStepId(id);
		step.setNodeType(type);
		step.setConfigJson(config);
		step.setNextStepId(next);
		step.setYesStepId(yes);
		step.setNoStepId(no);
		when(stepMapper.findById(id)).thenReturn(step);
		return step;
	}

	private WorkflowInstance instance(long currentStepId, int retryCount) {
		WorkflowInstance instance = new WorkflowInstance();
		instance.setInstanceId(1L);
		instance.setCampaignId(10L);
		instance.setCustomerId(20L);
		instance.setCurrentStepId(currentStepId);
		instance.setRetryCount(retryCount);
		return instance;
	}

	@BeforeEach
	void setUp() {
		when(sendLogMapper.findStatusByInstanceStep(anyLong(), anyLong())).thenReturn(SendStatus.PENDING);
		when(sendLogMapper.findLatestSendLogId(anyLong(), eq(Channel.EMAIL))).thenReturn(5L);
	}

	@Test
	void SEND_바로_뒤_WAIT는_발송_결과를_기다린다() {
		step(100, NodeType.SEND_EMAIL, "{}", 101L, null, null);
		step(101, NodeType.WAIT, "{\"amount\":2,\"unit\":\"DAY\"}", 102L, null, null);
		when(instanceMapper.moveToWaitPending(anyLong(), anyLong())).thenReturn(1);

		engine.processOne(instance(100, 0));

		verify(instanceMapper).moveToWaitPending(1L, 102L);
		verify(instanceMapper, never()).moveToWait(anyLong(), anyLong(), any());
	}

	@Test
	void 같은_캐시를_공유하는_인스턴스들은_단계를_한_번만_읽는다() {
		step(100, NodeType.SEND_EMAIL, "{}", 101L, null, null);
		step(101, NodeType.WAIT, "{\"amount\":2,\"unit\":\"DAY\"}", 102L, null, null);
		when(instanceMapper.moveToWaitPending(anyLong(), anyLong())).thenReturn(1);

		var cache = new HashMap<Long, WorkflowStep>();
		for (int i = 0; i < 3; i++) {
			engine.processOne(instance(100, 0), cache);
		}

		verify(stepMapper, times(1)).findById(100L);
		verify(stepMapper, times(1)).findById(101L);
		verify(instanceMapper, times(3)).moveToWaitPending(1L, 102L); // 캐시를 써도 실행 결과는 인스턴스마다 기록된다
	}

	@Test
	void 캐시를_넘기지_않는_단건_실행은_매번_단계를_읽는다() {
		step(100, NodeType.SEND_EMAIL, "{}", 101L, null, null);
		step(101, NodeType.WAIT, "{\"amount\":2,\"unit\":\"DAY\"}", 102L, null, null);
		when(instanceMapper.moveToWaitPending(anyLong(), anyLong())).thenReturn(1);

		engine.processOne(instance(100, 0));
		engine.processOne(instance(100, 0));

		verify(stepMapper, times(2)).findById(100L);
	}

	@Test
	void SEND_PENDING_뒤_CONDITION을_지난_WAIT는_발송_결과를_기다리지_않고_바로_대기_시각을_계산한다() {
		// SEND(PENDING) → CONDITION → WAIT: wake 는 SEND 바로 뒤 WAIT 만 깨우므로 NULL 로 두면 영원히 멈춘다
		step(100, NodeType.SEND_EMAIL, "{}", 101L, null, null);
		step(101, NodeType.CONDITION, "{\"condition\":\"EMAIL_CLICKED\"}", null, 102L, 102L);
		step(102, NodeType.WAIT, "{\"amount\":2,\"unit\":\"DAY\"}", 103L, null, null);
		when(trackEvents.existsHumanEvent(anyLong(), anyString())).thenReturn(false);
		when(instanceMapper.moveToWait(anyLong(), anyLong(), any())).thenReturn(1);

		engine.processOne(instance(100, 0));

		verify(instanceMapper).moveToWait(eq(1L), eq(103L), any());
		verify(instanceMapper, never()).moveToWaitPending(anyLong(), anyLong());
	}

	@Test
	void 두_번째_실패는_재시도하고_세_번째_실패에서_FAILED가_된다() {
		when(stepMapper.findById(anyLong())).thenThrow(new IllegalStateException("DB 오류"));
		when(instanceMapper.recordRetry(anyLong(), any(), anyString())).thenReturn(1);
		when(instanceMapper.recordFailed(anyLong(), anyString())).thenReturn(1);

		engine.processOne(instance(100, 1)); // 이번이 2번째 실패
		verify(instanceMapper).recordRetry(eq(1L), any(), anyString());
		verify(instanceMapper, never()).recordFailed(anyLong(), anyString());

		engine.processOne(instance(100, 2)); // 이번이 3번째 실패 — PRD 6.5-5 "3회 실패하면 FAILED"
		verify(instanceMapper).recordFailed(eq(1L), anyString());
	}

	@Test
	void 결과_기록이_0행이어도_예외를_던지지_않는다() {
		// 실행 중 캠페인 종료로 CANCELLED 가 된 인스턴스 — 기록 UPDATE 가 0행이어도 경고만 남긴다
		step(100, NodeType.END, "{}", null, null, null);
		when(instanceMapper.complete(anyLong())).thenReturn(0);

		engine.processOne(instance(100, 0));

		verify(instanceMapper).complete(1L);
		verify(instanceMapper, never()).recordRetry(anyLong(), any(), anyString());
	}

	@Test
	void 설정_맵에_unit이_없으면_재시도로_기록된다() {
		step(100, NodeType.WAIT, "{\"amount\":1}", 101L, null, null);
		when(instanceMapper.recordRetry(anyLong(), any(), any())).thenReturn(1);

		engine.processOne(instance(100, 0));

		verify(instanceMapper).recordRetry(eq(1L), any(), any());
	}
}
