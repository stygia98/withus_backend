package com.withus.campaign;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.withus.campaign.domain.SendKind;
import com.withus.campaign.domain.SendLog;
import com.withus.campaign.domain.Template;
import com.withus.campaign.mapper.SendLogMapper;
import com.withus.campaign.mapper.TemplateMapper;
import com.withus.campaign.service.MessageComposer;
import com.withus.campaign.service.SendDispatcher;
import com.withus.workflow.service.WorkflowWakeup;
import com.withus.campaign.service.messaging.MessageSenderRouter;
import com.withus.campaign.service.messaging.OutboundMessage;
import com.withus.campaign.service.messaging.SendResult;
import com.withus.common.domain.Channel;
import com.withus.common.token.UnsubscribeTokens;
import com.withus.customer.service.ConsentService;

/**
 * 발송 결과 기록 실패의 격리 (PR #21 2차 리뷰 🔴2·🔴3) — DB 없이 Mockito 로 도는 단위 테스트.
 * 발송이 이미 나간 뒤의 기록 실패는 재시도(=재발송)로 이어지면 안 되고, 재시도 기록까지 실패해도
 * 배치의 나머지 건은 계속 처리돼야 한다
 */
class SendDispatcherFailureIsolationTest {

	private SendLogMapper sendLogMapper;
	private TemplateMapper templateMapper;
	private MessageSenderRouter router;
	private ConsentService consentService;
	private MessageComposer composer;
	private SendDispatcher dispatcher;

	@BeforeEach
	void setUp() {
		sendLogMapper = mock(SendLogMapper.class);
		templateMapper = mock(TemplateMapper.class);
		router = mock(MessageSenderRouter.class);
		consentService = mock(ConsentService.class);
		composer = mock(MessageComposer.class);
		UnsubscribeTokens tokens = mock(UnsubscribeTokens.class);
		when(tokens.issue(anyLong(), anyLong())).thenReturn("token");
		dispatcher = new SendDispatcher(sendLogMapper, templateMapper, router, consentService, composer, tokens, mock(WorkflowWakeup.class), 1000,
			"08:00", "20:50", "https://withus.local", false);

		Template template = new Template();
		template.setAdYn("N");
		when(templateMapper.findById(5L)).thenReturn(template);
		when(consentService.isSendable(anyLong(), any())).thenReturn(true);
		when(sendLogMapper.findCampaignStatus(anyLong())).thenReturn("ACTIVE");
		when(composer.compose(any(), any(), anyString(), anyString())).thenReturn(Optional
			.of(new OutboundMessage(Channel.EMAIL, "to@withus.local", "제목", "본문", Map.of())));
	}

	private static SendLog log(long sendLogId, long campaignId) {
		return SendLog.builder().sendLogId(sendLogId).campaignId(campaignId).customerId(10L + sendLogId)
			.channel(Channel.EMAIL).kind(SendKind.CAMPAIGN).build();
	}

	@Test
	void 발송_성공_뒤_기록이_실패해도_재시도로_돌리지_않는다() {
		SendLog sendLog = log(1L, 1L);
		when(sendLogMapper.claimBatch()).thenReturn(List.of(sendLog), List.of());
		when(templateMapper.findTemplateIdByCampaignId(1L)).thenReturn(5L);
		when(router.send(any())).thenReturn(SendResult.success("msg-1"));
		when(sendLogMapper.recordSent(anyLong(), anyString())).thenThrow(new RuntimeException("db down"));

		dispatcher.dispatch();

		// 이미 나간 메일이다 — 재시도(PENDING 복귀)하면 같은 메일이 한 번 더 나간다
		verify(sendLogMapper, never()).recordRetry(anyLong(), any(), any());
		verify(sendLogMapper, never()).recordFailed(anyLong(), any());
	}

	@Test
	void 재시도_기록까지_실패해도_배치의_나머지_건은_처리한다() {
		SendLog broken = log(1L, 1L);
		SendLog good = log(2L, 2L);
		when(sendLogMapper.claimBatch()).thenReturn(List.of(broken, good), List.of());
		when(templateMapper.findTemplateIdByCampaignId(1L)).thenThrow(new RuntimeException("template lookup failed"));
		when(templateMapper.findTemplateIdByCampaignId(2L)).thenReturn(5L);
		when(sendLogMapper.recordRetry(anyLong(), any(), any())).thenThrow(new RuntimeException("db down"));
		when(router.send(any())).thenReturn(SendResult.success("msg-2"));

		dispatcher.dispatch();

		verify(sendLogMapper).recordSent(2L, "msg-2");
	}
}
