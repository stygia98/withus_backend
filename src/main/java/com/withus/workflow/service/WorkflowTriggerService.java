package com.withus.workflow.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.withus.campaign.domain.Campaign;
import com.withus.campaign.mapper.CampaignMapper;
import com.withus.common.exception.BusinessException;
import com.withus.common.exception.CommonErrorCode;
import com.withus.customer.domain.CustomerRegisteredEvent;
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

	private static final Logger log = LoggerFactory.getLogger(WorkflowTriggerService.class);

	/** 10만 건이 하나의 긴 트랜잭션이 되지 않도록 나누는 단위 (워크플로우 Plan 6.1) */
	private static final int BATCH_SIZE = 500;

	private final CampaignMapper campaignMapper;
	private final WorkflowStepMapper workflowStepMapper;
	private final WorkflowInstanceMapper workflowInstanceMapper;
	private final SegmentService segmentService;
	private final TransactionTemplate transactionTemplate;

	public WorkflowTriggerService(CampaignMapper campaignMapper, WorkflowStepMapper workflowStepMapper,
			WorkflowInstanceMapper workflowInstanceMapper, SegmentService segmentService,
			PlatformTransactionManager transactionManager) {
		this.campaignMapper = campaignMapper;
		this.workflowStepMapper = workflowStepMapper;
		this.workflowInstanceMapper = workflowInstanceMapper;
		this.segmentService = segmentService;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
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

	/**
	 * CUSTOMER_REGISTERED: 개별 등록된 고객이 ACTIVE 캠페인의 세그먼트에 해당하면 인스턴스를 만든다.
	 * 등록 트랜잭션이 커밋된 뒤에 처리하므로 여기서 실패해도 고객 등록은 롤백되지 않는다(워크플로우 Plan 6.2, PL 리뷰 R4).
	 * 캠페인마다 트랜잭션을 따로 연다 — PostgreSQL 은 SQL 오류 한 번에 트랜잭션 전체가 abort 되므로, 하나로 묶으면 한 캠페인의
	 * 오류가 다른 캠페인의 진입까지 잃게 한다(PR #35 리뷰 🟡5). 업로드 등록은 이벤트가 발행되지 않는다(F-01)
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onCustomerRegistered(CustomerRegisteredEvent event) {
		for (Campaign campaign : campaignMapper.findActiveCustomerRegistered()) {
			try {
				transactionTemplate.executeWithoutResult(status -> enterIfMember(campaign, event.customerId()));
			} catch (RuntimeException e) {
				// 캠페인 하나의 구조 오류가 다른 캠페인의 진입을 막지 않게 한다
				log.error("신규 가입 트리거 처리 실패 campaignId={} customerId={}", campaign.getCampaignId(), event.customerId(), e);
			}
		}
	}

	private void enterIfMember(Campaign campaign, long customerId) {
		// ponytail: 세그먼트 전체 조회 후 contains, 등록이 잦아지면 SegmentService.isMember 추가 검토
		if (segmentService.findTargetCustomers(campaign.getSegmentId()).contains(customerId)) {
			workflowInstanceMapper.insertBatch(campaign.getCampaignId(), findFirstStepId(campaign.getCampaignId()),
				List.of(customerId));
		}
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
