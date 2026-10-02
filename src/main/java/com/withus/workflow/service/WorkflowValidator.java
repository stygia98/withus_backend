package com.withus.workflow.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.withus.workflow.domain.NodeType;
import com.withus.workflow.domain.WorkflowCheck;
import com.withus.workflow.domain.WorkflowNode;
import com.withus.workflow.domain.WorkflowValidationResult;
import com.withus.workflow.domain.WorkflowWarning;

/**
 * 워크플로우 구조 검증 (PRD 6.4, API_SPEC 6장 POST /workflow/validate). DB·요청 DTO 어느 쪽과도 무관한
 * 순수 클래스 — 템플릿이 {{couponUrl}}을 쓰는지, 쿠폰이 유효기간 안인지는 호출하는 쪽(API 3/3)이 조회해서
 * 인자로 넘긴다.
 */
public class WorkflowValidator {

	private static final int MAX_NODES = 15;
	private static final int MAX_CONDITION_DEPTH = 2;
	private static final int MIN_WAIT_MINUTES = 1;
	private static final int MAX_WAIT_MINUTES = 90 * 24 * 60;

	/**
	 * @param templateUsesCouponUrl templateId → 본문에 {{couponUrl}}이 있는가
	 * @param couponValid           couponId → 오늘이 유효기간 안인가 (정보 없는 couponId는 유효한 것으로 본다)
	 */
	public WorkflowValidationResult validate(List<WorkflowNode> nodes, Map<Long, Boolean> templateUsesCouponUrl,
			Map<Long, Boolean> couponValid) {
		Map<String, WorkflowNode> byId = nodes.stream().collect(Collectors.toMap(WorkflowNode::id, n -> n));
		List<WorkflowCheck> checks = new ArrayList<>();
		List<WorkflowWarning> warnings = new ArrayList<>();

		List<WorkflowNode> triggers = nodes.stream().filter(n -> n.nodeType() == NodeType.TRIGGER).toList();
		checks.add(new WorkflowCheck("TRIGGER_COUNT", triggers.size() == 1,
			"TRIGGER 노드 " + triggers.size() + "개 (정확히 1개여야 함)"));

		checks.add(new WorkflowCheck("NODE_COUNT", nodes.size() <= MAX_NODES,
			"노드 " + nodes.size() + " / " + MAX_NODES + "개"));

		List<String> badWaitNodes = nodes.stream().filter(n -> n.nodeType() == NodeType.WAIT)
			.filter(n -> !waitWithinRange(n)).map(WorkflowNode::id).toList();
		checks.add(new WorkflowCheck("WAIT_DURATION_WITHIN_RANGE", badWaitNodes.isEmpty(),
			badWaitNodes.isEmpty() ? "WAIT 대기 시간 전부 1분~90일 이내" : "대기 시간이 범위를 벗어난 WAIT 노드: " + badWaitNodes));

		List<String> missingCouponNodes = nodes.stream()
			.filter(n -> n.nodeType() == NodeType.SEND_EMAIL || n.nodeType() == NodeType.SEND_SMS)
			.filter(n -> Boolean.TRUE.equals(templateUsesCouponUrl.get(n.templateId())) && n.couponId() == null)
			.map(WorkflowNode::id).toList();
		checks.add(new WorkflowCheck("COUPON_URL_REQUIRES_COUPON", missingCouponNodes.isEmpty(),
			missingCouponNodes.isEmpty() ? "{{couponUrl}} 템플릿은 전부 쿠폰이 연결돼 있음"
				: "{{couponUrl}} 템플릿인데 쿠폰이 없는 노드: " + missingCouponNodes));

		List<String> expiredCouponNodes = nodes.stream().filter(n -> n.couponId() != null)
			.filter(n -> Boolean.FALSE.equals(couponValid.get(n.couponId()))).map(WorkflowNode::id).toList();
		checks.add(new WorkflowCheck("COUPON_WITHIN_VALID_PERIOD", expiredCouponNodes.isEmpty(),
			expiredCouponNodes.isEmpty() ? "연결된 쿠폰 전부 유효기간 안"
				: "쿠폰이 유효기간 밖인 노드: " + expiredCouponNodes));

		if (triggers.size() == 1) {
			DfsAccumulator acc = new DfsAccumulator();
			traverse(triggers.get(0).id(), byId, new LinkedHashSet<>(), 0, false, false, acc, warnings);
			checks.add(new WorkflowCheck("NO_CYCLE", !acc.hasCycle, acc.hasCycle ? "순환 구조가 있음" : "순환 없음"));
			checks.add(new WorkflowCheck("DEPTH_WITHIN_LIMIT", !acc.depthExceeded,
				acc.depthExceeded ? "CONDITION 중첩이 2단계를 넘음" : "분기 중첩 2단계 이내"));
			checks.add(new WorkflowCheck("ALL_PATHS_END_WITH_END", acc.deadEnds.isEmpty(),
				acc.deadEnds.isEmpty() ? "모든 경로가 END로 끝남" : "END로 끝나지 않는 노드: " + acc.deadEnds));
			checks.add(new WorkflowCheck("EMAIL_EVENT_REQUIRES_SEND_WAIT", acc.badEmailConditions.isEmpty(),
				acc.badEmailConditions.isEmpty() ? "메일 이벤트 조건 앞에 SEND_EMAIL→WAIT 전부 있음"
					: "SEND_EMAIL→WAIT 없이 메일 이벤트 조건을 쓰는 노드: " + acc.badEmailConditions));
		}

		boolean valid = checks.stream().allMatch(WorkflowCheck::passed);
		return new WorkflowValidationResult(valid, checks, warnings);
	}

	private boolean waitWithinRange(WorkflowNode node) {
		Integer amount = node.waitAmount();
		String unit = node.waitUnit();
		if (amount == null || unit == null) {
			return false;
		}
		long minutes = switch (unit) {
			case "MINUTE" -> amount;
			case "HOUR" -> amount * 60L;
			case "DAY" -> amount * 1440L;
			default -> -1L;
		};
		return minutes >= MIN_WAIT_MINUTES && minutes <= MAX_WAIT_MINUTES;
	}

	/** 한 경로(TRIGGER부터 지금까지 지나온 노드) 안에서의 DFS 상태를 들고 다니는 누산기 */
	private static final class DfsAccumulator {
		boolean hasCycle;
		boolean depthExceeded;
		final List<String> deadEnds = new ArrayList<>();
		final List<String> badEmailConditions = new ArrayList<>();
	}

	private void traverse(String nodeId, Map<String, WorkflowNode> byId, Set<String> visitedOnPath,
			int conditionDepth, boolean sawSendEmail, boolean emailWaitSatisfied, DfsAccumulator acc,
			List<WorkflowWarning> warnings) {
		if (visitedOnPath.contains(nodeId)) {
			acc.hasCycle = true;
			return;
		}
		WorkflowNode node = byId.get(nodeId);
		if (node == null) {
			acc.deadEnds.add(nodeId); // 존재하지 않는 노드를 가리킴 — END에 닿을 수 없다
			return;
		}
		Set<String> path = new LinkedHashSet<>(visitedOnPath);
		path.add(nodeId);

		switch (node.nodeType()) {
			case END:
				return;
			case TRIGGER:
				goNext(node, path, conditionDepth, sawSendEmail, emailWaitSatisfied, byId, acc, warnings);
				return;
			case SEND_EMAIL:
				goNext(node, path, conditionDepth, true, emailWaitSatisfied, byId, acc, warnings);
				return;
			case SEND_SMS:
				goNext(node, path, conditionDepth, sawSendEmail, emailWaitSatisfied, byId, acc, warnings);
				return;
			case WAIT:
				goNext(node, path, conditionDepth, sawSendEmail, emailWaitSatisfied || sawSendEmail, byId, acc,
					warnings);
				return;
			case CONDITION:
				handleCondition(node, path, conditionDepth, sawSendEmail, emailWaitSatisfied, byId, acc, warnings);
				return;
		}
	}

	private void goNext(WorkflowNode node, Set<String> path, int conditionDepth, boolean sawSendEmail,
			boolean emailWaitSatisfied, Map<String, WorkflowNode> byId, DfsAccumulator acc,
			List<WorkflowWarning> warnings) {
		if (node.next() == null) {
			acc.deadEnds.add(node.id());
			return;
		}
		traverse(node.next(), byId, path, conditionDepth, sawSendEmail, emailWaitSatisfied, acc, warnings);
	}

	private void handleCondition(WorkflowNode node, Set<String> path, int conditionDepth, boolean sawSendEmail,
			boolean emailWaitSatisfied, Map<String, WorkflowNode> byId, DfsAccumulator acc,
			List<WorkflowWarning> warnings) {
		int depth = conditionDepth + 1;
		if (depth > MAX_CONDITION_DEPTH) {
			acc.depthExceeded = true;
		}
		String condition = node.condition();
		boolean emailEventCondition = "EMAIL_OPENED".equals(condition) || "EMAIL_CLICKED".equals(condition);
		if (emailEventCondition && !emailWaitSatisfied) {
			acc.badEmailConditions.add(node.id());
		}
		if ("EMAIL_OPENED".equals(condition)) {
			warnings.add(new WorkflowWarning("EMAIL_OPENED_UNRELIABLE",
				node.id() + ": 열람 조건 대신 클릭 조건 사용을 권장합니다."));
		}
		if (node.yes() == null || node.no() == null) {
			acc.deadEnds.add(node.id());
			return;
		}
		traverse(node.yes(), byId, path, depth, sawSendEmail, emailWaitSatisfied, acc, warnings);
		traverse(node.no(), byId, path, depth, sawSendEmail, emailWaitSatisfied, acc, warnings);
	}
}
