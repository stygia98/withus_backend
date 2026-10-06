package com.withus.workflow.dto;

import java.time.OffsetDateTime;

import com.withus.workflow.domain.InstanceStatus;
import com.withus.workflow.domain.WorkflowInstance;

/** 인스턴스 목록 한 줄 (API_SPEC 6장). 고객 개인정보는 싣지 않고 customerId 만 준다 */
public record WorkflowInstanceResponse(long instanceId, long customerId, long currentStepId, InstanceStatus status,
		OffsetDateTime nextRunAt, int retryCount, String lastError, OffsetDateTime createdAt,
		OffsetDateTime updatedAt) {

	public static WorkflowInstanceResponse from(WorkflowInstance i) {
		return new WorkflowInstanceResponse(i.getInstanceId(), i.getCustomerId(), i.getCurrentStepId(), i.getStatus(),
			i.getNextRunAt(), i.getRetryCount(), i.getLastError(), i.getCreatedAt(), i.getUpdatedAt());
	}
}
