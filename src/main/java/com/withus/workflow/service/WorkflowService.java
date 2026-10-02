package com.withus.workflow.service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.withus.campaign.domain.Campaign;
import com.withus.campaign.domain.CampaignErrorCode;
import com.withus.campaign.domain.CampaignStatus;
import com.withus.campaign.domain.CampaignType;
import com.withus.campaign.domain.Template;
import com.withus.campaign.mapper.SendLogMapper;
import com.withus.campaign.mapper.TemplateMapper;
import com.withus.campaign.service.CampaignService;
import com.withus.common.exception.BusinessException;
import com.withus.common.exception.CommonErrorCode;
import com.withus.workflow.domain.NodeType;
import com.withus.workflow.domain.WorkflowCheck;
import com.withus.workflow.domain.WorkflowErrorCode;
import com.withus.workflow.domain.WorkflowNode;
import com.withus.workflow.domain.WorkflowStep;
import com.withus.workflow.domain.WorkflowValidationResult;
import com.withus.workflow.dto.WorkflowResponse;
import com.withus.workflow.dto.WorkflowSaveRequest;
import com.withus.workflow.dto.WorkflowStepRequest;
import com.withus.workflow.dto.WorkflowStepResponse;
import com.withus.workflow.mapper.WorkflowStepMapper;

import tools.jackson.databind.ObjectMapper;

/** 워크플로우 구조 저장·조회·검증 (API_SPEC 6장, PRD 6.4) */
@Service
public class WorkflowService {

	private final WorkflowStepMapper workflowStepMapper;
	private final CampaignService campaignService;
	private final TemplateMapper templateMapper;
	private final SendLogMapper sendLogMapper;
	private final WorkflowValidator workflowValidator;
	private final ObjectMapper objectMapper;

	public WorkflowService(WorkflowStepMapper workflowStepMapper, CampaignService campaignService,
			TemplateMapper templateMapper, SendLogMapper sendLogMapper, WorkflowValidator workflowValidator,
			ObjectMapper objectMapper) {
		this.workflowStepMapper = workflowStepMapper;
		this.campaignService = campaignService;
		this.templateMapper = templateMapper;
		this.sendLogMapper = sendLogMapper;
		this.workflowValidator = workflowValidator;
		this.objectMapper = objectMapper;
	}

	public WorkflowResponse get(long campaignId) {
		requireWorkflow(campaignService.getOrThrow(campaignId));
		List<WorkflowStepResponse> steps = workflowStepMapper.findByCampaignId(campaignId).stream()
			.map(this::toResponse).toList();
		return new WorkflowResponse(steps);
	}

	/** 구조 검사만 하고 저장하지 않는다 (POST /workflow/validate) */
	public WorkflowValidationResult validateOnly(long campaignId, WorkflowSaveRequest request) {
		requireWorkflow(campaignService.getOrThrow(campaignId));
		return validate(request.steps());
	}

	/** DRAFT 상태의 워크플로우 캠페인만 저장한다. 기존 구조를 통째로 지우고 다시 쓴다 */
	@Transactional
	public WorkflowResponse save(long campaignId, WorkflowSaveRequest request) {
		Campaign campaign = campaignService.getOrThrow(campaignId);
		requireWorkflow(campaign);
		if (campaign.getStatus() != CampaignStatus.DRAFT) {
			throw new BusinessException(CampaignErrorCode.CAMPAIGN_INVALID_STATUS, "DRAFT 상태의 워크플로우만 저장할 수 있습니다.",
				null);
		}

		List<WorkflowStepRequest> steps = request.steps();
		WorkflowValidationResult result = validate(steps);
		if (!result.valid()) {
			List<String> violations = result.checks().stream().filter(c -> !c.passed()).map(WorkflowCheck::message)
				.toList();
			throw new BusinessException(WorkflowErrorCode.WORKFLOW_INVALID_STRUCTURE,
				WorkflowErrorCode.WORKFLOW_INVALID_STRUCTURE.message(), violations);
		}

		Map<String, Integer> depthByKey = computeDepths(steps);

		workflowStepMapper.deleteByCampaignId(campaignId);

		List<WorkflowStep> toInsert = steps.stream().map(s -> {
			WorkflowStep step = new WorkflowStep();
			step.setCampaignId(campaignId);
			step.setNodeType(s.nodeType());
			step.setConfigJson(objectMapper.writeValueAsString(s.config()));
			step.setDepth(depthByKey.getOrDefault(s.key(), 0).shortValue());
			return step;
		}).toList();
		workflowStepMapper.insertBatch(toInsert);

		Map<String, Long> keyToStepId = new HashMap<>();
		for (int i = 0; i < steps.size(); i++) {
			keyToStepId.put(steps.get(i).key(), toInsert.get(i).getStepId());
		}
		for (int i = 0; i < steps.size(); i++) {
			WorkflowStepRequest s = steps.get(i);
			workflowStepMapper.updateLinks(toInsert.get(i).getStepId(), keyToStepId.get(s.next()),
				keyToStepId.get(s.yes()), keyToStepId.get(s.no()));
		}

		return get(campaignId);
	}

	private void requireWorkflow(Campaign campaign) {
		if (campaign.getType() != CampaignType.WORKFLOW) {
			throw new BusinessException(CommonErrorCode.COMMON_INVALID_INPUT, "워크플로우 캠페인만 지원합니다.", null);
		}
	}

	private WorkflowValidationResult validate(List<WorkflowStepRequest> steps) {
		List<WorkflowNode> nodes = steps.stream()
			.map(s -> new WorkflowNode(s.key(), s.nodeType(), s.config(), s.next(), s.yes(), s.no())).toList();

		Set<Long> templateIds = nodes.stream().map(WorkflowNode::templateId).filter(Objects::nonNull)
			.collect(Collectors.toSet());
		Map<Long, Boolean> templateUsesCouponUrl = templateIds.stream()
			.collect(Collectors.toMap(id -> id, this::usesCouponUrl));

		Set<Long> couponIds = nodes.stream().map(WorkflowNode::couponId).filter(Objects::nonNull)
			.collect(Collectors.toSet());
		Map<Long, Boolean> couponValid = couponIds.stream()
			.collect(Collectors.toMap(id -> id, sendLogMapper::isCouponValid));

		return workflowValidator.validate(nodes, templateUsesCouponUrl, couponValid);
	}

	private boolean usesCouponUrl(long templateId) {
		Template template = templateMapper.findById(templateId);
		return template != null
			&& (CampaignService.containsCouponUrl(template.getSubject())
				|| CampaignService.containsCouponUrl(template.getBody()));
	}

	/** WorkflowValidator 가 이미 순환·미연결을 걸러냈다는 전제로, TRIGGER부터 CONDITION 중첩 수를 센다 */
	private Map<String, Integer> computeDepths(List<WorkflowStepRequest> steps) {
		Map<String, WorkflowStepRequest> byKey = steps.stream()
			.collect(Collectors.toMap(WorkflowStepRequest::key, s -> s));
		Map<String, Integer> depths = new HashMap<>();
		steps.stream().filter(s -> s.nodeType() == NodeType.TRIGGER).map(WorkflowStepRequest::key).findFirst()
			.ifPresent(triggerKey -> assignDepth(triggerKey, 0, byKey, depths, new HashSet<>()));
		return depths;
	}

	private void assignDepth(String key, int depth, Map<String, WorkflowStepRequest> byKey,
			Map<String, Integer> depths, Set<String> visited) {
		if (key == null || !visited.add(key)) {
			return;
		}
		depths.put(key, depth);
		WorkflowStepRequest node = byKey.get(key);
		if (node == null) {
			return;
		}
		if (node.nodeType() == NodeType.CONDITION) {
			assignDepth(node.yes(), depth + 1, byKey, depths, visited);
			assignDepth(node.no(), depth + 1, byKey, depths, visited);
		} else {
			assignDepth(node.next(), depth, byKey, depths, visited);
		}
	}

	private WorkflowStepResponse toResponse(WorkflowStep step) {
		@SuppressWarnings("unchecked")
		Map<String, Object> config = objectMapper.readValue(step.getConfigJson(), Map.class);
		return new WorkflowStepResponse(step.getStepId(), step.getNodeType(), config, step.getNextStepId(),
			step.getYesStepId(), step.getNoStepId(), step.getDepth());
	}
}
