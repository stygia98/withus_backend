package com.withus.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.withus.campaign.domain.SendLog;
import com.withus.campaign.mapper.SendLogMapper;
import com.withus.campaign.service.SendRecoveryJob;
import com.withus.customer.domain.CustomerDeletedEvent;
import com.withus.workflow.mapper.WorkflowInstanceMapper;
import com.withus.workflow.service.WorkflowCustomerListener;
import com.withus.workflow.service.WorkflowRecoveryJob;
import com.withus.workflow.service.WorkflowWakeup;

/** 인스턴스가 영원히 멈추지 않게 하는 경로들 (PR #35 리뷰 🔴1, 🟡6) — DB 없이 실행된다 */
class WorkflowWakeRecoveryTest {

	SendLogMapper sendLogMapper = mock(SendLogMapper.class);
	WorkflowInstanceMapper instanceMapper = mock(WorkflowInstanceMapper.class);
	WorkflowWakeup wakeup = mock(WorkflowWakeup.class);

	private SendLog sendLog(long id) {
		return SendLog.builder().sendLogId(id).instanceId(9L).stepId(1L).build();
	}

	@Test
	void SENDING_10분_초과로_FAILED가_된_건마다_인스턴스를_깨운다() {
		SendLog a = sendLog(1);
		SendLog b = sendLog(2);
		when(sendLogMapper.recoverStuckSending()).thenReturn(List.of(a, b));
		SendRecoveryJob job = new SendRecoveryJob(sendLogMapper, wakeup, false);

		int recovered = job.recover();

		assertThat(recovered).isEqualTo(2);
		verify(wakeup).wake(a);
		verify(wakeup).wake(b);
	}

	@Test
	void 깨우기가_한_건_실패해도_나머지를_깨운다() {
		SendLog a = sendLog(1);
		SendLog b = sendLog(2);
		when(sendLogMapper.recoverStuckSending()).thenReturn(List.of(a, b));
		doThrow(new IllegalStateException("config 파싱 오류")).when(wakeup).wake(a);
		SendRecoveryJob job = new SendRecoveryJob(sendLogMapper, wakeup, false);

		job.recover();

		verify(wakeup).wake(b);
	}

	@Test
	void 발송_결과를_기다리는데_직전_발송이_끝난_인스턴스를_보정으로_깨운다() {
		SendLog done = sendLog(7);
		when(sendLogMapper.findTerminalSendsOfWaitingInstances()).thenReturn(List.of(done));
		WorkflowRecoveryJob job = new WorkflowRecoveryJob(instanceMapper, sendLogMapper, wakeup, false);

		int tried = job.wakeOrphans();

		assertThat(tried).isEqualTo(1);
		verify(wakeup).wake(done);
	}

	@Test
	void 고객이_삭제되면_진행_중_인스턴스를_취소한다() {
		new WorkflowCustomerListener(instanceMapper).onCustomerDeleted(new CustomerDeletedEvent(42L));

		verify(instanceMapper).cancelActiveByCustomer(42L);
	}
}
