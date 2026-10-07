package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.withus.campaign.domain.SendKind;
import com.withus.campaign.domain.SendLog;
import com.withus.campaign.domain.Template;
import com.withus.campaign.mapper.SendLogMapper;
import com.withus.campaign.mapper.TemplateMapper;
import com.withus.campaign.service.MessageComposer;
import com.withus.campaign.service.SendDispatcher;
import com.withus.campaign.service.messaging.MessageSenderRouter;
import com.withus.campaign.service.messaging.OutboundMessage;
import com.withus.campaign.service.messaging.SendResult;
import com.withus.common.domain.Channel;
import com.withus.common.token.UnsubscribeTokens;
import com.withus.customer.service.ConsentService;
import com.withus.workflow.service.WorkflowWakeup;

/**
 * 정상 종료(인터럽트) 경로 — SendDispatcher.dispatch() 가 TokenBucket 대기 중 인터럽트를 받으면, 아직 처리하지 못한 선점분
 * (현재 건 포함)을 PENDING 으로 되돌리고 시도 횟수(attempt_count)를 소모하지 않는다 (PR #21 리뷰, 이슈 #90 항목 1).
 * 정상 종료는 선점만 된 건이 UNKNOWN_RESULT 로 누락되는 것을 막는 유일한 경로다(강제 종료 땐 누락, 설계대로).
 * DB 없이 Mockito 로 도는 단위 테스트다. 인터럽트 시점은 sleep 으로 맞추지 않고 스레드 상태(TIMED_WAITING = TokenBucket 의
 * Thread.sleep 대기)를 폴링해 정확히 대기 중일 때만 건다. 실제 DB·커넥션 풀이 인터럽트 상태에서도 되돌리기를 하는지는
 * SendDispatcherInterruptRevertTest(통합)가 본다
 */
class SendDispatcherInterruptRevertUnitTest {

	private SendLogMapper sendLogMapper;
	private MessageSenderRouter router;
	private SendDispatcher dispatcher;
	private ExecutorService executor;

	@BeforeEach
	void setUp() {
		sendLogMapper = mock(SendLogMapper.class);
		TemplateMapper templateMapper = mock(TemplateMapper.class);
		router = mock(MessageSenderRouter.class);
		ConsentService consentService = mock(ConsentService.class);
		MessageComposer composer = mock(MessageComposer.class);
		UnsubscribeTokens tokens = mock(UnsubscribeTokens.class);
		when(tokens.issue(anyLong(), anyLong())).thenReturn("token");
		// 초당 1건 — 시작 토큰은 가득(1개)이라 첫 건은 바로 나가고 둘째 건부터 TokenBucket 에서 약 1초 대기한다
		dispatcher = new SendDispatcher(sendLogMapper, templateMapper, router, consentService, composer, tokens,
			mock(WorkflowWakeup.class), 1, "08:00", "20:50", "https://withus.local", false);

		Template template = new Template();
		template.setAdYn("N");
		when(templateMapper.findById(5L)).thenReturn(template);
		when(templateMapper.findTemplateIdByCampaignId(1L)).thenReturn(5L);
		when(consentService.isSendable(anyLong(), any())).thenReturn(true);
		when(sendLogMapper.findCampaignStatus(anyLong())).thenReturn("ACTIVE");
		when(composer.compose(any(), any(), anyString(), anyString())).thenReturn(Optional
			.of(new OutboundMessage(Channel.EMAIL, "to@withus.local", "제목", "본문", Map.of())));
		when(router.send(any())).thenReturn(SendResult.success("msg"));
		executor = Executors.newSingleThreadExecutor();
	}

	@AfterEach
	void tearDown() {
		executor.shutdownNow();
	}

	private static SendLog log(long sendLogId) {
		return SendLog.builder().sendLogId(sendLogId).campaignId(1L).customerId(10L + sendLogId)
			.channel(Channel.EMAIL).kind(SendKind.CAMPAIGN).build();
	}

	@Test
	void 대기_중_인터럽트를_받으면_미처리_선점분을_PENDING으로_되돌리고_시도를_소모하지_않는다() throws Exception {
		List<SendLog> claimed = List.of(log(1L), log(2L), log(3L), log(4L));
		when(sendLogMapper.claimBatch()).thenReturn(claimed, List.of());

		AtomicReference<Thread> dispatchThread = new AtomicReference<>();
		Future<?> done = executor.submit(() -> {
			dispatchThread.set(Thread.currentThread());
			dispatcher.dispatch();
		});

		// 첫 건은 바로 나가고, 둘째 건이 TokenBucket.acquire 의 sleep 에서 대기(TIMED_WAITING)할 때까지 기다린다
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (dispatchThread.get() == null || dispatchThread.get().getState() != Thread.State.TIMED_WAITING) {
			assertThat(System.nanoTime()).as("TokenBucket 대기에 들어가야 한다").isLessThan(deadline);
			Thread.sleep(2);
		}
		dispatchThread.get().interrupt();
		done.get(5, TimeUnit.SECONDS); // 인터럽트를 받고 dispatch() 가 끝나야 한다(남은 건을 계속 처리하지 않는다)

		// 이미 나간 첫 건은 그대로 기록되고, 현재 건(2)과 그 뒤(3·4)가 PENDING 으로 돌아온다
		verify(router, times(1)).send(any());
		verify(sendLogMapper).recordSent(1L, "msg");
		verify(sendLogMapper, never()).revertToPending(1L);
		verify(sendLogMapper).revertToPending(2L);
		verify(sendLogMapper).revertToPending(3L);
		verify(sendLogMapper).revertToPending(4L);
		// 되돌린 건은 시도로 세지 않는다 — 재시도 기록(attempt_count 증가)도 실패 기록도 없다
		verify(sendLogMapper, never()).recordRetry(anyLong(), any(), any());
		verify(sendLogMapper, never()).recordFailed(anyLong(), any());
	}
}
