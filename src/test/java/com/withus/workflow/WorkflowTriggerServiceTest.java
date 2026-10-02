package com.withus.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.LongStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.withus.campaign.domain.Campaign;
import com.withus.campaign.mapper.CampaignMapper;
import com.withus.common.exception.BusinessException;
import com.withus.customer.domain.CustomerRegisteredEvent;
import com.withus.segment.service.SegmentService;
import com.withus.workflow.domain.NodeType;
import com.withus.workflow.domain.WorkflowStep;
import com.withus.workflow.mapper.WorkflowInstanceMapper;
import com.withus.workflow.mapper.WorkflowStepMapper;
import com.withus.workflow.service.WorkflowTriggerService;

/** SEGMENT_SCHEDULED 일괄 생성 (트리거 1/3) — 500건 분할과 시작 노드 결정. DB 없이 실행된다 */
class WorkflowTriggerServiceTest {

	CampaignMapper campaignMapper = mock(CampaignMapper.class);
	WorkflowStepMapper stepMapper = mock(WorkflowStepMapper.class);
	WorkflowInstanceMapper instanceMapper = mock(WorkflowInstanceMapper.class);
	SegmentService segmentService = mock(SegmentService.class);
	WorkflowTriggerService service = new WorkflowTriggerService(campaignMapper, stepMapper, instanceMapper,
		segmentService, mock(org.springframework.transaction.PlatformTransactionManager.class));

	@BeforeEach
	void setUp() {
		Campaign campaign = new Campaign();
		campaign.setSegmentId(7L);
		when(campaignMapper.findById(1L)).thenReturn(campaign);
	}

	private WorkflowStep trigger(Long nextStepId) {
		WorkflowStep step = new WorkflowStep();
		step.setNodeType(NodeType.TRIGGER);
		step.setNextStepId(nextStepId);
		return step;
	}

	@Test
	void 천이백명을_오백건씩_세번에_나눠_TRIGGER_다음_노드로_만든다() {
		when(stepMapper.findByCampaignId(1L)).thenReturn(List.of(trigger(42L)));
		when(segmentService.findTargetCustomers(7L)).thenReturn(LongStream.range(0, 1200).boxed().toList());
		when(instanceMapper.insertBatch(eq(1L), eq(42L), anyList())).thenAnswer(i -> ((List<?>) i.getArgument(2)).size());

		int created = service.startSegmentScheduled(1L);

		assertThat(created).isEqualTo(1200);
		ArgumentCaptor<List<Long>> chunks = ArgumentCaptor.forClass(List.class);
		verify(instanceMapper, times(3)).insertBatch(eq(1L), eq(42L), chunks.capture());
		assertThat(chunks.getAllValues()).extracting(List::size).containsExactly(500, 500, 200);
	}

	@Test
	void TRIGGER_노드가_없으면_거절한다() {
		when(stepMapper.findByCampaignId(1L)).thenReturn(List.of());

		assertThatThrownBy(() -> service.startSegmentScheduled(1L)).isInstanceOf(BusinessException.class);
		verify(instanceMapper, times(0)).insertBatch(anyLong(), anyLong(), anyList());
	}

	private Campaign registeredCampaign(long campaignId, long segmentId) {
		Campaign campaign = new Campaign();
		campaign.setCampaignId(campaignId);
		campaign.setSegmentId(segmentId);
		return campaign;
	}

	@Test
	void 신규_고객이_세그먼트에_해당하면_인스턴스를_만들고_아니면_만들지_않는다() {
		when(campaignMapper.findActiveCustomerRegistered())
			.thenReturn(List.of(registeredCampaign(10L, 100L), registeredCampaign(20L, 200L)));
		when(segmentService.findTargetCustomers(100L)).thenReturn(List.of(5L, 6L));
		when(segmentService.findTargetCustomers(200L)).thenReturn(List.of(6L));
		when(stepMapper.findByCampaignId(10L)).thenReturn(List.of(trigger(42L)));

		service.onCustomerRegistered(new CustomerRegisteredEvent(5L));

		verify(instanceMapper).insertBatch(10L, 42L, List.of(5L));
		verify(instanceMapper, never()).insertBatch(eq(20L), anyLong(), anyList());
	}

	@Test
	void 한_캠페인의_구조_오류가_다른_캠페인_진입을_막지_않는다() {
		when(campaignMapper.findActiveCustomerRegistered())
			.thenReturn(List.of(registeredCampaign(10L, 100L), registeredCampaign(20L, 100L)));
		when(segmentService.findTargetCustomers(100L)).thenReturn(List.of(5L));
		when(stepMapper.findByCampaignId(10L)).thenReturn(List.of()); // TRIGGER 없음 → 예외
		when(stepMapper.findByCampaignId(20L)).thenReturn(List.of(trigger(43L)));

		service.onCustomerRegistered(new CustomerRegisteredEvent(5L));

		verify(instanceMapper).insertBatch(20L, 43L, List.of(5L));
	}
}
