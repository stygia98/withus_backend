package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.withus.campaign.domain.Campaign;
import com.withus.campaign.domain.CampaignErrorCode;
import com.withus.campaign.domain.CampaignStatus;
import com.withus.campaign.domain.CampaignType;
import com.withus.campaign.domain.SendKind;
import com.withus.campaign.domain.Template;
import com.withus.campaign.mapper.CampaignMapper;
import com.withus.campaign.mapper.SendLogMapper;
import com.withus.campaign.mapper.TemplateMapper;
import com.withus.campaign.service.CampaignService;
import com.withus.campaign.service.SendQueueService;
import com.withus.common.domain.Channel;
import com.withus.common.exception.BusinessException;
import com.withus.segment.service.SegmentService;

/** start() 순서 (PR #31 리뷰 🔴1) — 적재가 ACTIVE 전환보다 먼저여야 한다. DB 없이 실행된다 */
class CampaignStartOrderTest {

	CampaignMapper campaignMapper = mock(CampaignMapper.class);
	TemplateMapper templateMapper = mock(TemplateMapper.class);
	SegmentService segmentService = mock(SegmentService.class);
	SendQueueService sendQueueService = mock(SendQueueService.class);
	// 읽은 시각, 시작 선점 뒤 시각 (이슈 #52 낙관적 CAS)
	static final OffsetDateTime READ_AT = OffsetDateTime.parse("2026-10-05T09:00:00+09:00");
	static final OffsetDateTime CLAIMED_AT = OffsetDateTime.parse("2026-10-05T09:00:05+09:00");

	SendLogMapper sendLogMapper = mock(SendLogMapper.class);
	CampaignService service = new CampaignService(campaignMapper, templateMapper, sendLogMapper,
		segmentService, sendQueueService, mock(com.withus.workflow.service.WorkflowTriggerService.class),
		mock(com.withus.workflow.mapper.WorkflowInstanceMapper.class), mock(com.withus.workflow.mapper.WorkflowStepMapper.class),
		"08:00", "20:50", 14);

	private Campaign campaign(CampaignStatus status) {
		Campaign campaign = new Campaign();
		campaign.setCampaignId(1L);
		campaign.setType(CampaignType.ONE_TIME);
		campaign.setStatus(status);
		campaign.setSegmentId(7L);
		campaign.setTemplateId(3L);
		campaign.setUpdatedAt(READ_AT);
		when(campaignMapper.findById(1L)).thenReturn(campaign);
		when(campaignMapper.claimStart(1L, READ_AT)).thenReturn(CLAIMED_AT);
		return campaign;
	}

	private void template() {
		Template template = new Template();
		template.setChannel(Channel.EMAIL);
		template.setAdYn("N"); // 비광고라 시간창 검사를 건너뛴다
		when(templateMapper.findById(3L)).thenReturn(template);
		when(segmentService.findTargetCustomers(7L)).thenReturn(List.of(1L, 2L));
	}

	@Test
	void 적재가_ACTIVE_전환보다_먼저다() {
		campaign(CampaignStatus.DRAFT);
		template();
		when(campaignMapper.start(1L, READ_AT, CLAIMED_AT)).thenReturn(1);

		service.start(1L);

		InOrder order = inOrder(sendQueueService, campaignMapper);
		order.verify(sendQueueService).enqueueOneTime(1L, List.of(1L, 2L), Channel.EMAIL, SendKind.CAMPAIGN);
		order.verify(campaignMapper).start(1L, READ_AT, CLAIMED_AT);
	}

	@Test
	void 적재가_실패하면_ACTIVE로_바뀌지_않는다() {
		campaign(CampaignStatus.DRAFT);
		template();
		when(sendQueueService.enqueueOneTime(anyLong(), anyList(), any(), any()))
			.thenThrow(new IllegalStateException("DB 오류"));

		assertThatThrownBy(() -> service.start(1L)).isInstanceOf(IllegalStateException.class);

		verify(campaignMapper, never()).start(anyLong(), any(), any());
	}

	@Test
	void ACTIVE_캠페인에_시작을_부르면_대상_조회_없이_409다() {
		campaign(CampaignStatus.ACTIVE);

		assertThatThrownBy(() -> service.start(1L)).isInstanceOfSatisfying(BusinessException.class,
			e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.CAMPAIGN_INVALID_STATUS));
		verify(segmentService, never()).findTargetCustomers(anyLong());
	}

	@Test
	void 시작할_때_적재_전에_남은_PENDING부터_지운다() {
		campaign(CampaignStatus.DRAFT);
		template();
		when(campaignMapper.start(1L, READ_AT, CLAIMED_AT)).thenReturn(1);

		service.start(1L);

		InOrder order = inOrder(sendLogMapper, sendQueueService, campaignMapper);
		order.verify(sendLogMapper).deleteUnstartedCampaignPending(1L);
		order.verify(sendQueueService).enqueueOneTime(1L, List.of(1L, 2L), Channel.EMAIL, SendKind.CAMPAIGN);
		order.verify(campaignMapper).start(1L, READ_AT, CLAIMED_AT);
	}

	@Test
	void 없는_쿠폰으로_캠페인을_만들면_400이다() {
		Campaign campaign = new Campaign();
		campaign.setType(CampaignType.ONE_TIME);
		campaign.setSegmentId(7L);
		campaign.setTemplateId(3L);
		campaign.setCouponId(99L);
		when(campaignMapper.existsSegment(7L)).thenReturn(true);
		when(campaignMapper.existsCoupon(99L)).thenReturn(false);

		assertThatThrownBy(() -> service.create(campaign, 1L)).isInstanceOf(BusinessException.class);

		verify(campaignMapper, never()).insert(any());
	}

	@Test
	void 선점이_가장_먼저다() {
		campaign(CampaignStatus.DRAFT);
		template();
		when(campaignMapper.start(1L, READ_AT, CLAIMED_AT)).thenReturn(1);

		service.start(1L);

		InOrder order = inOrder(campaignMapper, sendLogMapper, sendQueueService);
		order.verify(campaignMapper).claimStart(1L, READ_AT);
		order.verify(sendLogMapper).deleteUnstartedCampaignPending(1L);
		order.verify(sendQueueService).enqueueOneTime(1L, List.of(1L, 2L), Channel.EMAIL, SendKind.CAMPAIGN);
		order.verify(campaignMapper).start(1L, READ_AT, CLAIMED_AT);
	}

	@Test
	void 동시에_시작해_선점에_실패하면_삭제도_적재도_하지_않고_409다() {
		campaign(CampaignStatus.DRAFT);
		template();
		when(campaignMapper.claimStart(1L, READ_AT)).thenReturn(null); // 다른 요청이 먼저 선점했다

		assertThatThrownBy(() -> service.start(1L)).isInstanceOfSatisfying(BusinessException.class,
			e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.CAMPAIGN_INVALID_STATUS));

		verify(sendLogMapper, never()).deleteUnstartedCampaignPending(anyLong());
		verify(sendQueueService, never()).enqueueOneTime(anyLong(), anyList(), any(), any());
		verify(campaignMapper, never()).start(anyLong(), any(), any());
	}

	@Test
	void 적재_중_예약_취소나_수정이_끼면_최종_전환이_막혀_409다() {
		campaign(CampaignStatus.SCHEDULED);
		template();
		when(campaignMapper.start(1L, READ_AT, CLAIMED_AT)).thenReturn(0); // updated_at 이 바뀌어 0행

		assertThatThrownBy(() -> service.start(1L)).isInstanceOfSatisfying(BusinessException.class,
			e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.CAMPAIGN_INVALID_STATUS));
	}

	@Test
	void 예약을_취소하면_시작이_실패해_남았을_수_있는_적재분을_치운다() {
		campaign(CampaignStatus.SCHEDULED);
		when(campaignMapper.cancelSchedule(1L)).thenReturn(1);

		service.cancelSchedule(1L);

		verify(sendLogMapper).deleteUnstartedCampaignPending(1L);
	}

	@Test
	void 예약_취소가_실패하면_적재분을_지우지_않는다() {
		campaign(CampaignStatus.ACTIVE);
		when(campaignMapper.cancelSchedule(1L)).thenReturn(0);

		assertThatThrownBy(() -> service.cancelSchedule(1L)).isInstanceOf(BusinessException.class);

		verify(sendLogMapper, never()).deleteUnstartedCampaignPending(anyLong());
	}

	@Test
	void 적재가_실패하면_내_선점을_푼다() {
		campaign(CampaignStatus.DRAFT);
		template();
		when(sendQueueService.enqueueOneTime(anyLong(), anyList(), any(), any()))
			.thenThrow(new IllegalStateException("DB 오류"));

		assertThatThrownBy(() -> service.start(1L)).isInstanceOf(IllegalStateException.class);

		verify(campaignMapper).releaseStart(1L, CLAIMED_AT);
	}

	@Test
	void 최종_전환이_막혀도_내_선점을_푼다() {
		campaign(CampaignStatus.SCHEDULED);
		template();
		when(campaignMapper.start(1L, READ_AT, CLAIMED_AT)).thenReturn(0);

		assertThatThrownBy(() -> service.start(1L)).isInstanceOf(BusinessException.class);

		verify(campaignMapper).releaseStart(1L, CLAIMED_AT);
	}

	@Test
	void 선점에_실패한_요청은_남의_선점을_풀지_않는다() {
		campaign(CampaignStatus.DRAFT);
		template();
		when(campaignMapper.claimStart(1L, READ_AT)).thenReturn(null);

		assertThatThrownBy(() -> service.start(1L)).isInstanceOf(BusinessException.class);

		verify(campaignMapper, never()).releaseStart(anyLong(), any());
	}
}
