package com.withus.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.withus.workflow.domain.NodeType;
import com.withus.workflow.domain.WorkflowNode;
import com.withus.workflow.domain.WorkflowValidationResult;
import com.withus.workflow.service.WorkflowValidator;

/** 워크플로우 구조 검증 (PRD 6.4, 워크플로우 구조 2/3) — DB 없이 도는 순수 단위 테스트 */
class WorkflowValidatorTest {

	private final WorkflowValidator validator = new WorkflowValidator();

	private static WorkflowNode node(String id, NodeType type, Map<String, Object> config, String next, String yes,
			String no) {
		return new WorkflowNode(id, type, config, next, yes, no);
	}

	private boolean checkPassed(WorkflowValidationResult result, String code) {
		return result.checks().stream().filter(c -> c.code().equals(code)).findFirst()
			.orElseThrow(() -> new AssertionError("체크 없음: " + code)).passed();
	}

	@Test
	void PRD_6_4_예시_구조는_전부_통과한다() {
		List<WorkflowNode> nodes = List.of(
			node("t", NodeType.TRIGGER, Map.of("triggerType", "CUSTOMER_REGISTERED"), "s1", null, null),
			node("s1", NodeType.SEND_EMAIL, Map.of("templateId", 21), "w1", null, null),
			node("w1", NodeType.WAIT, Map.of("amount", 2, "unit", "DAY"), "c1", null, null),
			node("c1", NodeType.CONDITION, Map.of("condition", "EMAIL_CLICKED"), null, "c2", "s4"),
			node("c2", NodeType.CONDITION, Map.of("condition", "PURCHASE_GTE", "amount", 100000), null, "s2", "s3"),
			node("s2", NodeType.SEND_EMAIL, Map.of("templateId", 22, "couponId", 5), "e1", null, null),
			node("s3", NodeType.SEND_EMAIL, Map.of("templateId", 23, "couponId", 6), "e2", null, null),
			node("s4", NodeType.SEND_SMS, Map.of("templateId", 30), "e3", null, null),
			node("e1", NodeType.END, Map.of(), null, null, null),
			node("e2", NodeType.END, Map.of(), null, null, null),
			node("e3", NodeType.END, Map.of(), null, null, null));
		Map<Long, Boolean> templateUsesCouponUrl = Map.of(22L, true, 23L, true);
		Map<Long, Boolean> couponValid = Map.of(5L, true, 6L, true);

		WorkflowValidationResult result = validator.validate(nodes, templateUsesCouponUrl, couponValid);

		assertThat(result.valid()).isTrue();
		assertThat(result.warnings()).isEmpty();
	}

	@Test
	void 노드가_16개면_NODE_COUNT가_실패한다() {
		List<WorkflowNode> nodes = new java.util.ArrayList<>();
		nodes.add(node("t", NodeType.TRIGGER, Map.of(), "w0", null, null));
		for (int i = 0; i < 13; i++) {
			String id = "w" + i;
			String next = i == 12 ? "end" : "w" + (i + 1);
			nodes.add(node(id, NodeType.WAIT, Map.of("amount", 1, "unit", "DAY"), next, null, null));
		}
		nodes.add(node("s", NodeType.SEND_SMS, Map.of("templateId", 1), "end", null, null));
		nodes.add(node("end", NodeType.END, Map.of(), null, null, null));
		assertThat(nodes).hasSize(16);

		WorkflowValidationResult result = validator.validate(nodes, Map.of(), Map.of());

		assertThat(result.valid()).isFalse();
		assertThat(checkPassed(result, "NODE_COUNT")).isFalse();
	}

	@Test
	void CONDITION이_3단계_중첩되면_DEPTH_WITHIN_LIMIT이_실패한다() {
		List<WorkflowNode> nodes = List.of(
			node("t", NodeType.TRIGGER, Map.of(), "c1", null, null),
			node("c1", NodeType.CONDITION, Map.of("condition", "PURCHASE_GTE", "amount", 1), null, "c2", "e1"),
			node("c2", NodeType.CONDITION, Map.of("condition", "PURCHASE_GTE", "amount", 1), null, "c3", "e2"),
			node("c3", NodeType.CONDITION, Map.of("condition", "PURCHASE_GTE", "amount", 1), null, "e3", "e4"),
			node("e1", NodeType.END, Map.of(), null, null, null),
			node("e2", NodeType.END, Map.of(), null, null, null),
			node("e3", NodeType.END, Map.of(), null, null, null),
			node("e4", NodeType.END, Map.of(), null, null, null));

		WorkflowValidationResult result = validator.validate(nodes, Map.of(), Map.of());

		assertThat(result.valid()).isFalse();
		assertThat(checkPassed(result, "DEPTH_WITHIN_LIMIT")).isFalse();
	}

	@Test
	void 순환_구조는_NO_CYCLE이_실패한다() {
		List<WorkflowNode> nodes = List.of(
			node("t", NodeType.TRIGGER, Map.of(), "s1", null, null),
			node("s1", NodeType.SEND_SMS, Map.of("templateId", 1), "s1", null, null));

		WorkflowValidationResult result = validator.validate(nodes, Map.of(), Map.of());

		assertThat(result.valid()).isFalse();
		assertThat(checkPassed(result, "NO_CYCLE")).isFalse();
	}

	@Test
	void END으로_안_끝나는_경로는_ALL_PATHS_END가_실패한다() {
		List<WorkflowNode> nodes = List.of(node("t", NodeType.TRIGGER, Map.of(), "s1", null, null),
			node("s1", NodeType.SEND_SMS, Map.of("templateId", 1), null, null, null));

		WorkflowValidationResult result = validator.validate(nodes, Map.of(), Map.of());

		assertThat(result.valid()).isFalse();
		assertThat(checkPassed(result, "ALL_PATHS_END_WITH_END")).isFalse();
	}

	@Test
	void SEND_EMAIL_WAIT_없이_EMAIL_CLICKED를_쓰면_실패한다() {
		List<WorkflowNode> nodes = List.of(node("t", NodeType.TRIGGER, Map.of(), "c1", null, null),
			node("c1", NodeType.CONDITION, Map.of("condition", "EMAIL_CLICKED"), null, "e1", "e2"),
			node("e1", NodeType.END, Map.of(), null, null, null),
			node("e2", NodeType.END, Map.of(), null, null, null));

		WorkflowValidationResult result = validator.validate(nodes, Map.of(), Map.of());

		assertThat(result.valid()).isFalse();
		assertThat(checkPassed(result, "EMAIL_EVENT_REQUIRES_SEND_WAIT")).isFalse();
	}

	@Test
	void couponUrl_템플릿인데_쿠폰이_없으면_실패한다() {
		List<WorkflowNode> nodes = List.of(node("t", NodeType.TRIGGER, Map.of(), "s1", null, null),
			node("s1", NodeType.SEND_EMAIL, Map.of("templateId", 99), "e1", null, null),
			node("e1", NodeType.END, Map.of(), null, null, null));

		WorkflowValidationResult result = validator.validate(nodes, Map.of(99L, true), Map.of());

		assertThat(result.valid()).isFalse();
		assertThat(checkPassed(result, "COUPON_URL_REQUIRES_COUPON")).isFalse();
	}

	@Test
	void EMAIL_OPENED를_쓰면_경고가_붙는다() {
		List<WorkflowNode> nodes = List.of(
			node("t", NodeType.TRIGGER, Map.of(), "s1", null, null),
			node("s1", NodeType.SEND_EMAIL, Map.of("templateId", 1), "w1", null, null),
			node("w1", NodeType.WAIT, Map.of("amount", 1, "unit", "DAY"), "c1", null, null),
			node("c1", NodeType.CONDITION, Map.of("condition", "EMAIL_OPENED"), null, "e1", "e2"),
			node("e1", NodeType.END, Map.of(), null, null, null),
			node("e2", NodeType.END, Map.of(), null, null, null));

		WorkflowValidationResult result = validator.validate(nodes, Map.of(), Map.of());

		assertThat(result.valid()).isTrue();
		assertThat(result.warnings()).extracting("code").containsExactly("EMAIL_OPENED_UNRELIABLE");
	}
}
