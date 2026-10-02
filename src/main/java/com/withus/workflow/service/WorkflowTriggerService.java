package com.withus.workflow.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.withus.campaign.domain.Campaign;
import com.withus.campaign.mapper.CampaignMapper;
import com.withus.common.exception.BusinessException;
import com.withus.common.exception.CommonErrorCode;
import com.withus.segment.service.SegmentService;
import com.withus.workflow.domain.NodeType;
import com.withus.workflow.domain.WorkflowStep;
import com.withus.workflow.mapper.WorkflowInstanceMapper;
import com.withus.workflow.mapper.WorkflowStepMapper;

/**
 * 워크플로우 인스턴스 생성 트리거 (PRD 6.2, 워크플로우 Plan 6장).
 * 외부 호출이 없는 DB 적재라 청크마다 짧게 커밋한다(호출자는 @Transactional 안에서 부르지 않는다).
 */
@Service
public class WorkflowTriggerService {

	/** 10만 건이 하나의 긴 트랜잭션이 되지 않도록 나누는 단위 (워크플로우 Plan 6.1) */
	private static final int BATCH_SIZE = 500;

	private final CampaignMapper campaignMapper;
	private final WorkflowStepMapper workflowStepMapper;
	private final WorkflowInstanceMapper workflowInstanceMapper;
	private final SegmentService segmentService;

	public WorkflowTriggerService(CampaignMapper campaignMapper, WorkflowStepMapper workflowStepMapper,
			WorkflowInstanceMapper workflowInstanceMapper, SegmentService segmentService) {
		this.campaignMapper = campaignMapper;
		this.workflowStepMapper = workflowStepMapper;
		this.workflowInstanceMapper = workflowInstanceMapper;
		this.segmentService = segmentService;
	}

	/**
	 * SEGMENT_SCHEDULED: 세그먼트 대상 전체의 인스턴스를 TRIGGER 다음 노드에서 시작하도록 만든다.
	 * 같은 캠페인으로 다시 불러도 멱등하다(uq_workflow_instance).
	 * @return 새로 만들어진 인스턴스 수
	 */
	public int startSegmentScheduled(long campaignId) {
		Campaign campaign = campaignMapper.findById(campaignId);
		long firstStepId = findFirstStepId(campaignId);
		List<Long> targetIds = segmentService.findTargetCustomers(campaign.getSegmentId());
		int created = 0;
		for (int from = 0; from < targetIds.size(); from += BATCH_SIZE) {
			List<Long> chunk = targetIds.subList(from, Math.min(from + BATCH_SIZE, targetIds.size()));
			created += workflowInstanceMapper.insertBatch(campaignId, firstStepId, chunk);
		}
		return created;
	}

	private long findFirstStepId(long campaignId) {
		return workflowStepMapper.findByCampaignId(campaignId).stream()
			.filter(step -> step.getNodeType() == NodeType.TRIGGER)
			.map(WorkflowStep::getNextStepId)
			.findFirst()
			.orElseThrow(() -> new BusinessException(CommonErrorCode.COMMON_INVALID_INPUT,
				"워크플로우 구조(TRIGGER 노드)가 없습니다.", null));
	}
}
