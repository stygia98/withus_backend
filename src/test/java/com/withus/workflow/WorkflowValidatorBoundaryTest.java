package com.withus.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.withus.workflow.domain.NodeType;
import com.withus.workflow.domain.WorkflowNode;
import com.withus.workflow.domain.WorkflowValidationResult;
import com.withus.workflow.service.WorkflowValidator;

/** 워크플로우 구조 검증의 경계값·우회·폭주 (PR #34 리뷰 🔴) — DB 없이 실행된다 */
class WorkflowValidatorBoundaryTest {

	private final WorkflowValidator validator = new WorkflowValidator();

	private static WorkflowNode node(String id, NodeType type, Map<String, Object> config, String next, String yes,
			String no) {
		return new WorkflowNode(id, type, config, next, yes, no);
	}

	private static WorkflowNode end(String id) {
		return node(id, NodeType.END, Map.of(), null, null, null);
	}

	private static WorkflowNode condition(String id, String cond, String yes, String no) {
		return node(id, NodeType.CONDITION, Map.of("condition", cond, "amount", 1), null, yes, no);
	}

	private boolean passed(WorkflowValidationResult result, String code) {
		return result.checks().stream().filter(c -> c.code().equals(code)).findFirst()
			.orElseThrow(() -> new AssertionError("체크 없음: " + code)).passed();
	}

	/** TRIGGER → WAIT(amount, unit) → END 한 줄 구조 */
	private WorkflowValidationResult waitOnly(Object amount, String unit) {
		return validator.validate(List.of(node("t", NodeType.TRIGGER, Map.of(), "w", null, null),
			node("w", NodeType.WAIT, Map.of("amount", amount, "unit", unit), "e", null, null), end("e")), Map.of(),
			Map.of());
	}

	@Test
	void 노드_15개는_통과하고_16개는_실패한다() {
		for (int total : new int[] { 15, 16 }) {
			List<WorkflowNode> nodes = new ArrayList<>();
			nodes.add(node("t", NodeType.TRIGGER, Map.of(), "w0", null, null));
			int waits = total - 2;
			for (int i = 0; i < waits; i++) {
				nodes.add(node("w" + i, NodeType.WAIT, Map.of("amount", 1, "unit", "DAY"),
					i == waits - 1 ? "e" : "w" + (i + 1), null, null));
			}
			nodes.add(end("e"));

			WorkflowValidationResult result = validator.validate(nodes, Map.of(), Map.of());

			assertThat(nodes).hasSize(total);
			assertThat(passed(result, "NODE_COUNT")).as("노드 %d개", total).isEqualTo(total == 15);
			assertThat(result.valid()).as("노드 %d개", total).isEqualTo(total == 15);
		}
	}

	@ParameterizedTest
	@CsvSource({ "1,MINUTE,true", "0,MINUTE,false", "129600,MINUTE,true", "129601,MINUTE,false", "2160,HOUR,true",
		"2161,HOUR,false", "90,DAY,true", "91,DAY,false", "-1,DAY,false" })
	void WAIT_대기_시간은_1분_이상_90일_이하만_통과한다(long amount, String unit, boolean expected) {
		assertThat(passed(waitOnly(amount, unit), "WAIT_DURATION_WITHIN_RANGE")).isEqualTo(expected);
	}

	@Test
	void int로_줄이면_범위_안이_되는_큰_수와_소수는_거부한다() {
		// 4294967297 = 2^32 + 1 → int 로 줄이면 1 이 되어 상한을 우회했다
		assertThat(passed(waitOnly(4294967297L, "MINUTE"), "WAIT_DURATION_WITHIN_RANGE")).isFalse();
		assertThat(passed(waitOnly(1.9, "MINUTE"), "WAIT_DURATION_WITHIN_RANGE")).isFalse();
		assertThat(passed(waitOnly(Long.MAX_VALUE, "DAY"), "WAIT_DURATION_WITHIN_RANGE")).isFalse();
	}

	@Test
	void 두_노드가_서로를_가리키는_간접_순환도_잡는다() {
		WorkflowValidationResult result = validator.validate(List.of(
			node("t", NodeType.TRIGGER, Map.of(), "a", null, null),
			node("a", NodeType.WAIT, Map.of("amount", 1, "unit", "DAY"), "b", null, null),
			node("b", NodeType.WAIT, Map.of("amount", 1, "unit", "DAY"), "a", null, null)), Map.of(), Map.of());

		assertThat(passed(result, "NO_CYCLE")).isFalse();
	}

	@Test
	void CONDITION_중첩_2단계는_통과한다() {
		List<WorkflowNode> two = List.of(node("t", NodeType.TRIGGER, Map.of(), "c1", null, null),
			condition("c1", "PURCHASE_GTE", "c2", "e1"), condition("c2", "PURCHASE_GTE", "e2", "e3"), end("e1"),
			end("e2"), end("e3"));

		assertThat(passed(validator.validate(two, Map.of(), Map.of()), "DEPTH_WITHIN_LIMIT")).isTrue();
	}

	@Test
	void TRIGGER가_0개나_2개면_실패하고_검사_코드_목록은_항상_같다() {
		List<String> expected = codes(validator.validate(
			List.of(node("t", NodeType.TRIGGER, Map.of(), "e", null, null), end("e")), Map.of(), Map.of()));

		WorkflowValidationResult none = validator.validate(List.of(end("e")), Map.of(), Map.of());
		WorkflowValidationResult two = validator.validate(List.of(node("t1", NodeType.TRIGGER, Map.of(), "e", null, null),
			node("t2", NodeType.TRIGGER, Map.of(), "e", null, null), end("e")), Map.of(), Map.of());

		assertThat(passed(none, "TRIGGER_COUNT")).isFalse();
		assertThat(passed(two, "TRIGGER_COUNT")).isFalse();
		assertThat(codes(none)).isEqualTo(expected);
		assertThat(codes(two)).isEqualTo(expected);
	}

	private List<String> codes(WorkflowValidationResult result) {
		return result.checks().stream().map(c -> c.code()).toList();
	}

	@Test
	void 쿠폰이_없거나_기간이_지났으면_실패한다() {
		List<WorkflowNode> nodes = List.of(node("t", NodeType.TRIGGER, Map.of(), "s", null, null),
			node("s", NodeType.SEND_SMS, Map.of("templateId", 1, "couponId", 9), "e", null, null), end("e"));

		assertThat(passed(validator.validate(nodes, Map.of(), Map.of()), "COUPON_WITHIN_VALID_PERIOD"))
			.as("맵에 없는 쿠폰").isFalse();
		assertThat(passed(validator.validate(nodes, Map.of(), Map.of(9L, false)), "COUPON_WITHIN_VALID_PERIOD"))
			.as("기간이 지난 쿠폰").isFalse();
		assertThat(passed(validator.validate(nodes, Map.of(), Map.of(9L, true)), "COUPON_WITHIN_VALID_PERIOD"))
			.isTrue();
	}

	@Test
	void TRIGGER에서_닿지_않는_고아_노드는_실패한다() {
		WorkflowValidationResult result = validator.validate(List.of(
			node("t", NodeType.TRIGGER, Map.of(), "e", null, null), end("e"),
			node("orphan", NodeType.SEND_SMS, Map.of("templateId", 1), "e", null, null)), Map.of(), Map.of());

		assertThat(passed(result, "ALL_NODES_REACHABLE")).isFalse();
		assertThat(result.valid()).isFalse();
	}

	@Test
	void 메일_이벤트_조건은_직전_SEND_EMAIL_뒤에_WAIT가_있어야_한다() {
		// A → WAIT → B → CONDITION: B 뒤에는 WAIT 가 없으므로 실패해야 한다
		WorkflowValidationResult result = validator.validate(List.of(
			node("t", NodeType.TRIGGER, Map.of(), "a", null, null),
			node("a", NodeType.SEND_EMAIL, Map.of("templateId", 1), "w", null, null),
			node("w", NodeType.WAIT, Map.of("amount", 1, "unit", "DAY"), "b", null, null),
			node("b", NodeType.SEND_EMAIL, Map.of("templateId", 2), "c", null, null),
			condition("c", "EMAIL_CLICKED", "e1", "e2"), end("e1"), end("e2")), Map.of(), Map.of());

		assertThat(passed(result, "EMAIL_EVENT_REQUIRES_SEND_WAIT")).isFalse();
	}

	@Test
	void 노드_id가_중복되거나_비어_있어도_예외_없이_실패로_보고한다() {
		WorkflowValidationResult dup = validator.validate(
			List.of(node("t", NodeType.TRIGGER, Map.of(), "e", null, null), end("e"), end("e")), Map.of(), Map.of());
		WorkflowValidationResult blank = validator.validate(
			List.of(node("t", NodeType.TRIGGER, Map.of(), null, null, null), end(null)), Map.of(), Map.of());

		assertThat(passed(dup, "UNIQUE_NODE_IDS")).isFalse();
		assertThat(passed(blank, "UNIQUE_NODE_IDS")).isFalse();
	}

	@Test
	void 설정이_없거나_타입이_틀려도_예외_없이_실패로_보고한다() {
		WorkflowValidationResult result = validator.validate(List.of(
			node("t", NodeType.TRIGGER, null, "c", null, null),
			node("c", NodeType.CONDITION, Map.of("condition", 123), null, "s", "e"),
			node("s", NodeType.SEND_EMAIL, Map.of(), "e", null, null), end("e")), Map.of(), Map.of());

		assertThat(passed(result, "CONFIG_VALID")).isFalse();
		assertThat(result.valid()).isFalse();
	}

	@Test
	void END에_다음_노드가_있거나_TRIGGER에_yes가_있으면_실패한다() {
		WorkflowValidationResult result = validator.validate(List.of(
			node("t", NodeType.TRIGGER, Map.of(), "e", "e", null), node("e", NodeType.END, Map.of(), "t", null, null)),
			Map.of(), Map.of());

		assertThat(passed(result, "LINKS_MATCH_NODE_TYPE")).isFalse();
	}

	@Test
	void 합류하는_CONDITION_30개와_5000개_WAIT_체인도_바로_끝난다() {
		// c_i 의 yes·no 가 모두 c_{i+1} — 경로를 전부 세는 방식이면 2^30 번 돈다
		List<WorkflowNode> diamond = new ArrayList<>();
		diamond.add(node("t", NodeType.TRIGGER, Map.of(), "c0", null, null));
		for (int i = 0; i < 30; i++) {
			String next = i == 29 ? "e" : "c" + (i + 1);
			diamond.add(condition("c" + i, "PURCHASE_GTE", next, next));
		}
		diamond.add(end("e"));
		List<WorkflowNode> chain = new ArrayList<>();
		chain.add(node("t", NodeType.TRIGGER, Map.of(), "w0", null, null));
		for (int i = 0; i < 5000; i++) {
			chain.add(node("w" + i, NodeType.WAIT, Map.of("amount", 1, "unit", "DAY"),
				i == 4999 ? "e" : "w" + (i + 1), null, null));
		}
		chain.add(end("e"));

		assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
			assertThat(validator.validate(diamond, Map.of(), Map.of()).valid()).isFalse();
			assertThat(validator.validate(chain, Map.of(), Map.of()).valid()).isFalse();
		});
	}

	@Test
	void 노드_15개_이하로_CONDITION이_합류해도_경고는_한_번만_붙는다() {
		WorkflowValidationResult result = validator.validate(List.of(
			node("t", NodeType.TRIGGER, Map.of(), "s", null, null),
			node("s", NodeType.SEND_EMAIL, Map.of("templateId", 1), "w", null, null),
			node("w", NodeType.WAIT, Map.of("amount", 1, "unit", "DAY"), "c1", null, null),
			condition("c1", "PURCHASE_GTE", "c2", "c2"), condition("c2", "EMAIL_OPENED", "e", "e"), end("e")),
			Map.of(), Map.of());

		assertThat(result.valid()).isTrue();
		assertThat(result.warnings()).hasSize(1);
	}
}
