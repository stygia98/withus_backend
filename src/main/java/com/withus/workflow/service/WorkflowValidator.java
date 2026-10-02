package com.withus.workflow.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.withus.workflow.domain.NodeType;
import com.withus.workflow.domain.WorkflowCheck;
import com.withus.workflow.domain.WorkflowNode;
import com.withus.workflow.domain.WorkflowValidationResult;
import com.withus.workflow.domain.WorkflowWarning;

/**
 * 워크플로우 구조 검증 (PRD 6.4, API_SPEC 6장 POST /workflow/validate). DB·요청 DTO 어느 쪽과도 무관한
 * 순수 클래스 — 템플릿이 {{couponUrl}}을 쓰는지, 쿠폰이 유효기간 안인지는 호출하는 쪽(API 3/3)이 조회해서
 * 인자로 넘긴다.
 * <p>항상 같은 검사 코드 목록을 돌려준다(구조에 따라 항목이 빠지면 프론트가 오류를 놓칠 수 있다, PR #34 리뷰).
 * 구조 검사(순환·깊이·END·메일 조건·도달 가능)는 TRIGGER 가 정확히 1개이고 노드 수·id 가 정상일 때만 돌리고,
 * 그렇지 않으면 "검사하지 못함"으로 실패 처리한다. 그래서 입력이 아무리 커도 탐색이 폭주하지 않는다.
 */
@Component
public class WorkflowValidator {

	private static final int MAX_NODES = 15;
	private static final int MAX_CONDITION_DEPTH = 2;
	private static final long MIN_WAIT_MINUTES = 1;
	private static final long MAX_WAIT_MINUTES = 90L * 24 * 60;
	private static final Set<String> CONDITIONS = Set.of("EMAIL_OPENED", "EMAIL_CLICKED", "PURCHASE_GTE");

	/**
	 * @param templateUsesCouponUrl templateId → 본문에 {{couponUrl}}이 있는가 (존재하는 템플릿만 넣는다)
	 * @param couponValid           couponId → 오늘이 유효기간 안인가. 맵에 없거나 false 면 쓸 수 없는 쿠폰으로 본다
	 */
	public WorkflowValidationResult validate(List<WorkflowNode> nodes, Map<Long, Boolean> templateUsesCouponUrl,
			Map<Long, Boolean> couponValid) {
		List<WorkflowCheck> checks = new ArrayList<>();
		List<WorkflowWarning> warnings = new ArrayList<>();

		List<WorkflowNode> triggers = nodes.stream().filter(n -> n.nodeType() == NodeType.TRIGGER).toList();
		boolean oneTrigger = triggers.size() == 1;
		checks.add(new WorkflowCheck("TRIGGER_COUNT", oneTrigger,
			"TRIGGER 노드 " + triggers.size() + "개 (정확히 1개여야 함)"));

		boolean nodeCountOk = nodes.size() <= MAX_NODES;
		checks.add(new WorkflowCheck("NODE_COUNT", nodeCountOk, "노드 " + nodes.size() + " / " + MAX_NODES + "개"));

		boolean idsOk = hasUniqueIds(nodes);
		checks.add(new WorkflowCheck("UNIQUE_NODE_IDS", idsOk,
			idsOk ? "노드 id 중복 없음" : "노드 id 가 비었거나 중복됨"));

		List<String> badLinks = nodes.stream().filter(n -> !linksMatchType(n)).map(WorkflowNode::id).toList();
		checks.add(new WorkflowCheck("LINKS_MATCH_NODE_TYPE", badLinks.isEmpty(),
			badLinks.isEmpty() ? "노드 종류에 맞는 연결만 있음 (END 는 연결 없음, CONDITION 은 yes·no, 그 외는 next)"
				: "노드 종류와 맞지 않는 연결이 있는 노드: " + badLinks));

		List<String> badConfig = nodes.stream().map(this::configProblem).filter(p -> p != null).toList();
		checks.add(new WorkflowCheck("CONFIG_VALID", badConfig.isEmpty(),
			badConfig.isEmpty() ? "노드 설정값 정상" : "설정이 잘못된 노드: " + badConfig));

		List<String> badWaitNodes = nodes.stream().filter(n -> n.nodeType() == NodeType.WAIT)
			.filter(n -> !waitWithinRange(n)).map(WorkflowNode::id).toList();
		checks.add(new WorkflowCheck("WAIT_DURATION_WITHIN_RANGE", badWaitNodes.isEmpty(),
			badWaitNodes.isEmpty() ? "WAIT 대기 시간 전부 1분~90일 이내" : "대기 시간이 범위를 벗어난 WAIT 노드: " + badWaitNodes));

		List<String> missingCouponNodes = sendNodes(nodes)
			.filter(n -> n.templateId() != null && Boolean.TRUE.equals(templateUsesCouponUrl.get(n.templateId()))
				&& n.couponId() == null)
			.map(WorkflowNode::id).toList();
		checks.add(new WorkflowCheck("COUPON_URL_REQUIRES_COUPON", missingCouponNodes.isEmpty(),
			missingCouponNodes.isEmpty() ? "{{couponUrl}} 템플릿은 전부 쿠폰이 연결돼 있음"
				: "{{couponUrl}} 템플릿인데 쿠폰이 없는 노드: " + missingCouponNodes));

		// 맵에 없는 쿠폰(삭제됐거나 없는 ID)은 유효하다고 보지 않는다 — 발송 직전에야 COUPON_INVALID 로 실패하는 걸 막는다
		List<String> badCouponNodes = nodes.stream().filter(n -> n.couponId() != null)
			.filter(n -> !Boolean.TRUE.equals(couponValid.get(n.couponId()))).map(WorkflowNode::id).toList();
		checks.add(new WorkflowCheck("COUPON_WITHIN_VALID_PERIOD", badCouponNodes.isEmpty(),
			badCouponNodes.isEmpty() ? "연결된 쿠폰 전부 유효기간 안"
				: "쿠폰이 없거나 유효기간 밖인 노드: " + badCouponNodes));

		if (oneTrigger && nodeCountOk && idsOk) {
			Walk walk = new Walk(nodes);
			walk.visit(triggers.get(0).id(), 0, false, false);
			List<String> unreachable = nodes.stream().map(WorkflowNode::id).filter(id -> !walk.reached.contains(id))
				.toList();
			checks.add(new WorkflowCheck("NO_CYCLE", !walk.hasCycle, walk.hasCycle ? "순환 구조가 있음" : "순환 없음"));
			checks.add(new WorkflowCheck("DEPTH_WITHIN_LIMIT", !walk.depthExceeded,
				walk.depthExceeded ? "CONDITION 중첩이 2단계를 넘음" : "분기 중첩 2단계 이내"));
			checks.add(new WorkflowCheck("ALL_PATHS_END_WITH_END", walk.deadEnds.isEmpty(),
				walk.deadEnds.isEmpty() ? "모든 경로가 END로 끝남" : "END로 끝나지 않는 노드: " + walk.deadEnds));
			checks.add(new WorkflowCheck("EMAIL_EVENT_REQUIRES_SEND_WAIT", walk.badEmailConditions.isEmpty(),
				walk.badEmailConditions.isEmpty() ? "메일 이벤트 조건 앞에 직전 SEND_EMAIL→WAIT 전부 있음"
					: "직전 SEND_EMAIL→WAIT 없이 메일 이벤트 조건을 쓰는 노드: " + walk.badEmailConditions));
			checks.add(new WorkflowCheck("ALL_NODES_REACHABLE", unreachable.isEmpty(),
				unreachable.isEmpty() ? "모든 노드가 TRIGGER 에서 닿음" : "TRIGGER 에서 닿지 않는 노드: " + unreachable));
			walk.openedConditions.forEach(id -> warnings.add(new WorkflowWarning("EMAIL_OPENED_UNRELIABLE",
				id + ": 열람 조건 대신 클릭 조건 사용을 권장합니다.")));
		} else {
			for (String code : List.of("NO_CYCLE", "DEPTH_WITHIN_LIMIT", "ALL_PATHS_END_WITH_END",
				"EMAIL_EVENT_REQUIRES_SEND_WAIT", "ALL_NODES_REACHABLE")) {
				checks.add(new WorkflowCheck(code, false,
					"검사하지 못함 — TRIGGER 1개, 노드 15개 이하, 노드 id 정상이어야 구조를 검사할 수 있음"));
			}
		}

		boolean valid = checks.stream().allMatch(WorkflowCheck::passed);
		return new WorkflowValidationResult(valid, checks, warnings);
	}

	private java.util.stream.Stream<WorkflowNode> sendNodes(List<WorkflowNode> nodes) {
		return nodes.stream().filter(n -> n.nodeType() == NodeType.SEND_EMAIL || n.nodeType() == NodeType.SEND_SMS);
	}

	private boolean hasUniqueIds(List<WorkflowNode> nodes) {
		Set<String> seen = new HashSet<>();
		for (WorkflowNode n : nodes) {
			if (n.id() == null || n.id().isBlank() || !seen.add(n.id())) {
				return false;
			}
		}
		return true;
	}

	/** END 는 연결 없음, CONDITION 은 yes·no 만, 그 외(TRIGGER·WAIT·SEND)는 next 만 — 엔진이 잘못된 링크를 보지 않게 한다 */
	private boolean linksMatchType(WorkflowNode n) {
		return switch (n.nodeType()) {
			case END -> n.next() == null && n.yes() == null && n.no() == null;
			case CONDITION -> n.next() == null;
			default -> n.yes() == null && n.no() == null;
		};
	}

	/** 설정에 문제가 있으면 "id: 사유", 없으면 null */
	private String configProblem(WorkflowNode n) {
		switch (n.nodeType()) {
			case SEND_EMAIL, SEND_SMS:
				return n.templateId() == null ? n.id() + ": templateId 없음" : null;
			case CONDITION:
				if (n.condition() == null || !CONDITIONS.contains(n.condition())) {
					return n.id() + ": condition 이 올바르지 않음";
				}
				if ("PURCHASE_GTE".equals(n.condition())) {
					Long amount = n.waitAmount();
					return amount == null || amount < 0 ? n.id() + ": PURCHASE_GTE 는 0 이상의 정수 amount 가 필요함" : null;
				}
				return null;
			default:
				return null;
		}
	}

	private boolean waitWithinRange(WorkflowNode node) {
		Long amount = node.waitAmount();
		String unit = node.waitUnit();
		if (amount == null || unit == null || amount < 1 || amount > MAX_WAIT_MINUTES) {
			return false; // 상한을 넘는 값은 곱셈 전에 걸러 오버플로를 막는다(1분 단위 상한이 가장 큰 수)
		}
		long minutes = switch (unit) {
			case "MINUTE" -> amount;
			case "HOUR" -> amount * 60L;
			case "DAY" -> amount * 1440L;
			default -> -1L;
		};
		return minutes >= MIN_WAIT_MINUTES && minutes <= MAX_WAIT_MINUTES;
	}

	/**
	 * TRIGGER 부터의 탐색. 같은 (노드, 중첩 깊이, 메일 상태) 는 한 번만 보므로 CONDITION 이 합류하는 구조에서도
	 * 경로 수가 폭발하지 않는다. 노드는 최대 15개, 재귀 깊이도 그 이하다.
	 * 메일 이벤트 조건은 "직전 SEND_EMAIL 뒤에 WAIT 가 있었는가"로 본다(PRD 6.3 직전 메일 기준) —
	 * SEND_EMAIL 을 만나면 WAIT 충족 여부를 다시 false 로 시작한다.
	 */
	private static final class Walk {
		final Map<String, WorkflowNode> byId = new HashMap<>();
		final Set<String> onPath = new HashSet<>();
		final Set<String> seenStates = new HashSet<>();
		final Set<String> reached = new HashSet<>();
		final Set<String> deadEnds = new LinkedHashSet<>();
		final Set<String> badEmailConditions = new LinkedHashSet<>();
		final Set<String> openedConditions = new LinkedHashSet<>();
		boolean hasCycle;
		boolean depthExceeded;

		Walk(List<WorkflowNode> nodes) {
			nodes.forEach(n -> byId.put(n.id(), n));
		}

		void visit(String id, int conditionDepth, boolean sawSendEmail, boolean waitedAfterEmail) {
			if (id == null) {
				return;
			}
			WorkflowNode node = byId.get(id);
			if (node == null) {
				deadEnds.add(id); // 존재하지 않는 노드를 가리킴 — END 에 닿을 수 없다
				return;
			}
			if (onPath.contains(id)) {
				hasCycle = true;
				return;
			}
			if (!seenStates.add(id + "|" + conditionDepth + "|" + sawSendEmail + "|" + waitedAfterEmail)) {
				return;
			}
			reached.add(id);
			onPath.add(id);
			switch (node.nodeType()) {
				case END -> {
				}
				case SEND_EMAIL -> next(node, conditionDepth, true, false);
				case WAIT -> next(node, conditionDepth, sawSendEmail, waitedAfterEmail || sawSendEmail);
				case CONDITION -> condition(node, conditionDepth, sawSendEmail, waitedAfterEmail);
				default -> next(node, conditionDepth, sawSendEmail, waitedAfterEmail); // TRIGGER·SEND_SMS
			}
			onPath.remove(id);
		}

		private void next(WorkflowNode node, int depth, boolean sawSendEmail, boolean waited) {
			if (node.next() == null) {
				deadEnds.add(node.id());
				return;
			}
			visit(node.next(), depth, sawSendEmail, waited);
		}

		private void condition(WorkflowNode node, int conditionDepth, boolean sawSendEmail, boolean waited) {
			int depth = conditionDepth + 1;
			if (depth > MAX_CONDITION_DEPTH) {
				depthExceeded = true;
			}
			String condition = node.condition();
			if (("EMAIL_OPENED".equals(condition) || "EMAIL_CLICKED".equals(condition)) && !waited) {
				badEmailConditions.add(node.id());
			}
			if ("EMAIL_OPENED".equals(condition)) {
				openedConditions.add(node.id());
			}
			if (node.yes() == null || node.no() == null) {
				deadEnds.add(node.id());
				return;
			}
			visit(node.yes(), depth, sawSendEmail, waited);
			visit(node.no(), depth, sawSendEmail, waited);
		}
	}
}
