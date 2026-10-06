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
		// 캠페인 행을 잠가서 확인한다(PR #34 리뷰 🔴2): 동시에 PUT 두 개가 오면 하나씩 처리되고, 시작 요청이 먼저 ACTIVE 로
		// 커밋했다면 여기서 409 가 된다. 잠그지 않으면 실행 중인 캠페인의 단계를 지우려다 FK 위반(500)이 날 수 있다
		if (!"DRAFT".equals(workflowStepMapper.lockCampaignStatus(campaignId))) {
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
			step.setConfigJson(objectMapper.writeValueAsString(s.config() == null ? Map.of() : s.config()));
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
			WorkflowStep step = toInsert.get(i);
			step.setNextStepId(keyToStepId.get(s.next()));
			step.setYesStepId(keyToStepId.get(s.yes()));
			step.setNoStepId(keyToStepId.get(s.no()));
		}
		workflowStepMapper.updateLinksBatch(toInsert);

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
		Map<Long, Boolean> templateUsesCouponUrl = new HashMap<>();
		List<Long> missingTemplates = new java.util.ArrayList<>();
		for (Long id : templateIds) {
			Template template = templateMapper.findById(id);
			if (template == null) {
				missingTemplates.add(id);
			} else {
				templateUsesCouponUrl.put(id, usesCouponUrl(template));
			}
		}
		Set<Long> couponIds = nodes.stream().map(WorkflowNode::couponId).filter(Objects::nonNull)
			.collect(Collectors.toSet());
		// 없는 쿠폰은 null 이라 false(쓸 수 없음)로 둔다 — 검증기가 "없거나 기간 밖"으로 보고한다
		Map<Long, Boolean> couponValid = couponIds.stream()
			.collect(Collectors.toMap(id -> id, id -> Boolean.TRUE.equals(sendLogMapper.isCouponValid(id))));

		return withTemplateExistsCheck(workflowValidator.validate(nodes, templateUsesCouponUrl, couponValid),
			missingTemplates);
	}

	/**
	 * 없는 템플릿을 예외가 아니라 검사 결과(TEMPLATE_EXISTS)로 보고한다 — POST /workflow/validate 도 "무엇이 틀렸는지"를 돌려줘야 한다
	 * (PR #34 리뷰). 저장(PUT)은 valid 가 false 면 WORKFLOW_INVALID_STRUCTURE 로 막는다
	 */
	private WorkflowValidationResult withTemplateExistsCheck(WorkflowValidationResult result,
			List<Long> missingTemplates) {
		boolean exists = missingTemplates.isEmpty();
		List<WorkflowCheck> checks = new java.util.ArrayList<>(result.checks());
		checks.add(new WorkflowCheck("TEMPLATE_EXISTS", exists,
			exists ? "SEND 노드의 템플릿이 전부 존재함" : "존재하지 않는 템플릿: " + missingTemplates));
		return new WorkflowValidationResult(result.valid() && exists, checks, result.warnings());
	}

	private boolean usesCouponUrl(Template template) {
		return CampaignService.containsCouponUrl(template.getSubject())
			|| CampaignService.containsCouponUrl(template.getBody());
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
