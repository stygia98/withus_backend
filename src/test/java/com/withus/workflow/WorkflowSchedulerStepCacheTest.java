package com.withus.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.withus.workflow.domain.WorkflowInstance;
import com.withus.workflow.domain.WorkflowStep;
import com.withus.workflow.mapper.WorkflowInstanceMapper;
import com.withus.workflow.service.WorkflowEngine;
import com.withus.workflow.service.WorkflowScheduler;

/** 단계 캐시는 선점한 묶음 안에서만 공유한다 — 다음 묶음(다음 선점)은 새로 읽는다. DB 없이 실행된다 */
class WorkflowSchedulerStepCacheTest {

	WorkflowInstanceMapper instanceMapper = mock(WorkflowInstanceMapper.class);
	WorkflowEngine engine = mock(WorkflowEngine.class);
	WorkflowScheduler scheduler = new WorkflowScheduler(instanceMapper, engine, true);

	@SuppressWarnings("unchecked")
	@Test
	void 한_묶음_안에서는_같은_캐시를_넘기고_다음_묶음은_새_캐시를_넘긴다() {
		WorkflowInstance a = new WorkflowInstance();
		WorkflowInstance b = new WorkflowInstance();
		WorkflowInstance c = new WorkflowInstance();
		when(instanceMapper.claimBatch()).thenReturn(List.of(a, b), List.of(c), List.of());

		scheduler.dispatch();

		ArgumentCaptor<Map<Long, WorkflowStep>> caches = ArgumentCaptor.forClass(Map.class);
		verify(engine, times(3)).processOne(any(WorkflowInstance.class), caches.capture());
		List<Map<Long, WorkflowStep>> used = caches.getAllValues();
		assertThat(used.get(0)).isSameAs(used.get(1)); // 같은 묶음
		assertThat(used.get(2)).isNotSameAs(used.get(0)); // 다음 묶음은 새 캐시 — 오래된 단계가 남지 않는다
	}
}
