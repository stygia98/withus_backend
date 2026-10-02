package com.withus.workflow.service;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.withus.customer.domain.CustomerDeletedEvent;
import com.withus.workflow.mapper.WorkflowInstanceMapper;

/**
 * 고객이 삭제되면 그 고객의 진행 중(WAITING·RUNNING) 인스턴스를 CANCELLED 로 바꾼다(PRD 6.5, 워크플로우 Plan 7장).
 * 삭제 트랜잭션 안에서 발행되므로 @EventListener 로 같은 트랜잭션에서 처리한다 — 삭제와 취소가 함께 커밋되거나 함께 롤백된다.
 */
@Component
public class WorkflowCustomerListener {

	private final WorkflowInstanceMapper workflowInstanceMapper;

	public WorkflowCustomerListener(WorkflowInstanceMapper workflowInstanceMapper) {
		this.workflowInstanceMapper = workflowInstanceMapper;
	}

	@EventListener
	public void onCustomerDeleted(CustomerDeletedEvent event) {
		workflowInstanceMapper.cancelActiveByCustomer(event.customerId());
	}
}
