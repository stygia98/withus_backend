package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.withus.campaign.domain.Campaign;
import com.withus.campaign.domain.CampaignErrorCode;
import com.withus.campaign.domain.CampaignStatus;
import com.withus.campaign.mapper.CampaignMapper;
import com.withus.campaign.mapper.SendLogMapper;
import com.withus.campaign.mapper.TemplateMapper;
import com.withus.campaign.service.CampaignService;
import com.withus.campaign.service.SendQueueService;
import com.withus.common.exception.BusinessException;
import com.withus.segment.service.SegmentService;
import com.withus.workflow.mapper.WorkflowInstanceMapper;
import com.withus.workflow.service.WorkflowTriggerService;

/** 일시정지·재개·종료 (상태 전이 1/3) — 낙관적 검사 결과(바뀐 행 수)에 따른 분기. DB 없이 실행된다 */
class CampaignTransitionTest {

	CampaignMapper campaignMapper = mock(CampaignMapper.class);
	WorkflowInstanceMapper instanceMapper = mock(WorkflowInstanceMapper.class);
	CampaignService service = new CampaignService(campaignMapper, mock(TemplateMapper.class),
		mock(SendLogMapper.class), mock(SegmentService.class), mock(SendQueueService.class),
		mock(WorkflowTriggerService.class), instanceMapper, "08:00", "20:50", 14);

	CampaignTransitionTest() {
		Campaign campaign = new Campaign();
		campaign.setStatus(CampaignStatus.ACTIVE);
		when(campaignMapper.findById(1L)).thenReturn(campaign);
	}

	@Test
	void ACTIVE이면_일시정지되고_PAUSED면_재개된다() {
		when(campaignMapper.updateStatus(1L, CampaignStatus.ACTIVE, CampaignStatus.PAUSED)).thenReturn(1);
		when(campaignMapper.updateStatus(1L, CampaignStatus.PAUSED, CampaignStatus.ACTIVE)).thenReturn(1);

		assertThat(service.pause(1L).getStatus()).isEqualTo(CampaignStatus.PAUSED);
		assertThat(service.resume(1L).getStatus()).isEqualTo(CampaignStatus.ACTIVE);
	}

	@Test
	void 전이표에_없는_일시정지_재개는_409다() {
		when(campaignMapper.updateStatus(anyLong(), org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any())).thenReturn(0);

		assertThatThrownBy(() -> service.pause(1L)).isInstanceOfSatisfying(BusinessException.class,
			e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.CAMPAIGN_INVALID_STATUS));
		assertThatThrownBy(() -> service.resume(1L)).isInstanceOf(BusinessException.class);
	}

	@Test
	void 종료하면_진행_중_인스턴스를_취소한다() {
		when(campaignMapper.completeManually(1L)).thenReturn(1);

		assertThat(service.complete(1L).getStatus()).isEqualTo(CampaignStatus.COMPLETED);
		verify(instanceMapper).cancelActiveByCampaign(1L);
	}

	@Test
	void 종료할_수_없는_상태면_409이고_인스턴스는_건드리지_않는다() {
		when(campaignMapper.completeManually(1L)).thenReturn(0);

		assertThatThrownBy(() -> service.complete(1L)).isInstanceOf(BusinessException.class);
		verify(instanceMapper, never()).cancelActiveByCampaign(anyLong());
	}
}
