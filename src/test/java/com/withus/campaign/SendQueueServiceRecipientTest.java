package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import com.withus.campaign.domain.CustomerRecipient;
import com.withus.campaign.domain.SendKind;
import com.withus.campaign.domain.SendLog;
import com.withus.campaign.domain.SendStatus;
import com.withus.campaign.mapper.SendLogMapper;
import com.withus.campaign.service.SendQueueService;
import com.withus.common.domain.Channel;
import com.withus.customer.service.ConsentService;

/** 수신처 없는 고객 제외와 동의 일괄 확인 (이슈 #43) — DB 없이 실행된다 */
class SendQueueServiceRecipientTest {

	SendLogMapper sendLogMapper = mock(SendLogMapper.class);
	ConsentService consentService = mock(ConsentService.class);
	SendQueueService service = new SendQueueService(sendLogMapper, consentService,
		mock(PlatformTransactionManager.class));

	private CustomerRecipient recipient(long customerId, String phone) {
		// @Getter 만 있는 프로젝션이라 리플렉션으로 채운다
		CustomerRecipient r = new CustomerRecipient();
		try {
			var id = CustomerRecipient.class.getDeclaredField("customerId");
			id.setAccessible(true);
			id.set(r, customerId);
			var p = CustomerRecipient.class.getDeclaredField("recipient");
			p.setAccessible(true);
			p.set(r, phone);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
		return r;
	}

	@Test
	void 휴대폰_없는_고객은_예외_없이_빠지고_나머지만_적재된다() {
		when(sendLogMapper.findRecipients(anyList(), any()))
			.thenReturn(List.of(recipient(1, "01011112222"), recipient(2, null), recipient(3, "01033334444")));
		when(consentService.filterSendable(anyList(), any())).thenReturn(Set.of(1L));
		when(sendLogMapper.insertWorkflowBatch(anyList())).thenReturn(1);

		service.enqueueWorkflowStep(10L, 20L, 30L, 2L, Channel.SMS); // 휴대폰 없는 고객 단건 → 적재 없음

		verify(sendLogMapper, org.mockito.Mockito.never()).insertWorkflowBatch(anyList());
	}

	@Test
	void 일회성_적재는_동의_확인을_청크당_한_번에_하고_거부는_SKIPPED다() {
		when(sendLogMapper.findRecipients(anyList(), any()))
			.thenReturn(List.of(recipient(1, "01011112222"), recipient(2, null), recipient(3, "01033334444")));
		when(consentService.filterSendable(anyList(), any())).thenReturn(Set.of(1L));
		when(sendLogMapper.insertOneTimeBatch(anyList())).thenReturn(2);

		service.enqueueOneTime(10L, List.of(1L, 2L, 3L), Channel.SMS, SendKind.CAMPAIGN);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<SendLog>> logs = ArgumentCaptor.forClass(List.class);
		verify(sendLogMapper).insertOneTimeBatch(logs.capture());
		assertThat(logs.getValue()).extracting(SendLog::getCustomerId).containsExactly(1L, 3L);
		assertThat(logs.getValue()).extracting(SendLog::getStatus)
			.containsExactly(SendStatus.PENDING, SendStatus.SKIPPED);
		verify(consentService).filterSendable(anyList(), any());
	}
}
