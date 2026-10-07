package com.withus.load;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;

import com.withus.segment.service.SegmentService;
import com.withus.workflow.service.WorkflowScheduler;
import com.withus.workflow.service.WorkflowTriggerService;

/**
 * PRD 10.3 "1만 명 대상 SEGMENT_SCHEDULED 워크플로우가 한 번의 스케줄 주기 안에서 모두 큐에 적재된다"를 잰다.
 * WorkflowTriggerServiceTest 는 1,200명을 500건씩 3묶음으로 나누는 것만 보므로, 여기서는 실제 1만 명 규모에서
 * ① 인스턴스 일괄 생성(startSegmentScheduled) ② 워크플로우 엔진 1회 실행(WorkflowScheduler.dispatch — 처리할 건이
 * 없을 때까지 반복하는 한 번의 스케줄 주기)이 각각 얼마나 걸리고 send_log 에 몇 건이 PENDING 으로 쌓이는지 출력한다.
 * 시간은 단언하지 않고 출력만 한다(건수만 단언: 대상 전원이 한 번의 dispatch 안에서 끝까지 적재돼야 한다).
 *
 * 흐름: TRIGGER → SEND_EMAIL(비광고) → END 인 SEGMENT_SCHEDULED 캠페인(ACTIVE). 고객은 [WFLOAD] 태그로 만들고
 * 끝에 직접 지운다(실패해도 다음 실행 시작 때 태그로 먼저 지운다). 실행:
 * ./mvnw test -Dtest=WorkflowSegmentScheduledLoadCheck -Dwithus.scheduler.send-dispatcher.enabled=false
 * 실제 커밋을 하므로 로컬 DB 에서만 실행한다(LoadQueueCheck 와 같은 가드). 일반 테스트 묶음에 넣지 않으려고 이름을 *Check 로 둔다.
 */
@DisabledIfEnvironmentVariable(named = "DB_URL", matches = "(?!.*//(localhost|127[.]0[.]0[.]1)[:/]).*",
	disabledReason = "DB_URL 이 로컬 DB(localhost·127.0.0.1)가 아니면 실행하지 않는다")
@DisabledIfEnvironmentVariable(named = "SPRING_PROFILES_ACTIVE", matches = "(?!.*local).+",
	disabledReason = "SPRING_PROFILES_ACTIVE 가 local 이 아니면 실행하지 않는다")
// 엔진은 이 테스트가 직접 한 번만 돌린다 — 백그라운드 스케줄이 끼어들면 "한 번의 주기" 측정이 흐려진다
@SpringBootTest(properties = {
	"withus.scheduler.send-dispatcher.enabled=false",
	"withus.scheduler.workflow-engine.enabled=false"
})
class WorkflowSegmentScheduledLoadCheck {

	private static final int N = 10_000;
	private static final String TAG = "[WFLOAD]";

	@Autowired
	Environment environment;
	@Value("${spring.datasource.url}")
	String datasourceUrl;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	SegmentService segmentService;
	@Autowired
	WorkflowTriggerService workflowTriggerService;
	@Autowired
	WorkflowScheduler workflowScheduler;

	@Test
	void 만명_SEGMENT_SCHEDULED가_한_번의_스케줄_주기_안에_모두_적재되는지() {
		assertThat(environment.acceptsProfiles(Profiles.of("local"))).as("local 프로필에서만 실행한다").isTrue();
		assertThat(datasourceUrl).as("로컬 DB(localhost·127.0.0.1)에서만 실행한다").containsAnyOf("//localhost", "//127.0.0.1");

		clean();
		try {
			long campaignId = prepare();
			int targets = segmentService.findTargetCustomers(segmentIdOf(campaignId)).size();

			long t0 = System.nanoTime();
			int created = workflowTriggerService.startSegmentScheduled(campaignId);
			long t1 = System.nanoTime();
			workflowScheduler.dispatch(); // 한 번의 스케줄 주기 = 처리할 건이 없을 때까지 반복하는 dispatch() 1회
			long t2 = System.nanoTime();

			Map<String, Object> instances = jdbc.queryForMap("SELECT "
				+ "count(*) AS total, count(*) FILTER (WHERE status = 'COMPLETED') AS completed, "
				+ "count(*) FILTER (WHERE status IN ('WAITING','RUNNING')) AS pending, "
				+ "count(*) FILTER (WHERE status = 'FAILED') AS failed "
				+ "FROM workflow_instance WHERE campaign_id = ?", campaignId);
			Integer sendLogs = jdbc.queryForObject(
				"SELECT count(*) FROM send_log WHERE step_id IN (SELECT step_id FROM workflow_step WHERE campaign_id = ?) "
					+ "AND status = 'PENDING'", Integer.class, campaignId);

			System.out.printf("%n[WFLOAD] 대상 %d명 · 인스턴스 생성 %d건 %.1f초 · 엔진 1회 %.1f초 · 합계 %.1f초%n",
				targets, created, (t1 - t0) / 1e9, (t2 - t1) / 1e9, (t2 - t0) / 1e9);
			System.out.printf("[WFLOAD] 인스턴스 상태 %s · PENDING send_log %d건 · 초당 %.0f건(엔진)%n", instances, sendLogs,
				targets / ((t2 - t1) / 1e9));

			// 시간은 단언하지 않는다. 건수만: 대상 전원이 한 번의 dispatch 안에서 인스턴스 생성 → 끝까지 실행 → 큐 적재까지 가야 한다
			assertThat(created).isEqualTo(targets);
			assertThat(((Number) instances.get("pending")).intValue()).as("한 번의 주기 뒤에도 남은 인스턴스").isZero();
			assertThat(sendLogs).as("큐에 적재된 건수").isEqualTo(targets);
			assertThat(targets).as("1만 명 규모 측정이어야 한다").isGreaterThanOrEqualTo(N);
		} finally {
			clean();
		}
	}

	private long segmentIdOf(long campaignId) {
		return jdbc.queryForObject("SELECT segment_id FROM campaign WHERE campaign_id = ?", Long.class, campaignId);
	}

	/** 고객 N명(동의 Y, 최근 가입) + 그 고객을 고르는 세그먼트 + TRIGGER→SEND_EMAIL→END 캠페인을 커밋해 둔다 */
	private long prepare() {
		jdbc.update("INSERT INTO member (email, password, name, role) VALUES ('wfload@wfload.withus.local', 'x', ?, 'MANAGER')",
			TAG + " 소유자");
		long memberId = jdbc.queryForObject("SELECT member_id FROM member WHERE email = 'wfload@wfload.withus.local'", Long.class);

		jdbc.update("INSERT INTO customer (email, joined_at, source, email_consent_yn) "
			+ "SELECT 'wfload-' || i || '@wfload.withus.local', now(), 'MANUAL', 'Y' FROM generate_series(1, ?) AS i", N);

		jdbc.update("INSERT INTO template (channel, name, subject, body, ad_yn, created_by) VALUES ('EMAIL', ?, '제목', '본문', 'N', ?)",
			TAG + " 비광고 메일", memberId);
		long templateId = jdbc.queryForObject("SELECT max(template_id) FROM template WHERE name = ?", Long.class, TAG + " 비광고 메일");

		jdbc.update("INSERT INTO segment (name, created_by) VALUES (?, ?)", TAG + " 대상", memberId);
		long segmentId = jdbc.queryForObject("SELECT max(segment_id) FROM segment WHERE name = ?", Long.class, TAG + " 대상");
		jdbc.update("INSERT INTO segment_rule (segment_id, rule_json) VALUES (?, ?::jsonb)", segmentId,
			"{\"operator\":\"AND\",\"groups\":[{\"operator\":\"AND\",\"conditions\":["
				+ "{\"field\":\"joinedAt\",\"op\":\"IN_LAST_DAYS\",\"value\":90},"
				+ "{\"field\":\"emailConsent\",\"op\":\"EQ\",\"value\":\"Y\"}]}]}");

		jdbc.update("INSERT INTO campaign (name, type, status, segment_id, trigger_type, started_at, created_by) "
			+ "VALUES (?, 'WORKFLOW', 'ACTIVE', ?, 'SEGMENT_SCHEDULED', now(), ?)", TAG + " 캠페인", segmentId, memberId);
		long campaignId = jdbc.queryForObject("SELECT max(campaign_id) FROM campaign WHERE name = ?", Long.class, TAG + " 캠페인");

		// END 부터 거꾸로 만들어 next_step_id 를 채운다
		long end = step(campaignId, "END", "{}", null);
		long send = step(campaignId, "SEND_EMAIL", "{\"templateId\":" + templateId + "}", end);
		step(campaignId, "TRIGGER", "{\"triggerType\":\"SEGMENT_SCHEDULED\"}", send);
		return campaignId;
	}

	private long step(long campaignId, String nodeType, String configJson, Long nextStepId) {
		jdbc.update("INSERT INTO workflow_step (campaign_id, node_type, config_json, next_step_id, depth) "
			+ "VALUES (?, ?, ?::jsonb, ?, 0)", campaignId, nodeType, configJson, nextStepId);
		return jdbc.queryForObject("SELECT max(step_id) FROM workflow_step WHERE campaign_id = ?", Long.class, campaignId);
	}

	/** [WFLOAD] 태그가 붙은 것만 지운다. 자식 → 부모 순서 */
	private void clean() {
		List<Long> campaigns = jdbc.queryForList("SELECT campaign_id FROM campaign WHERE name = ?", Long.class, TAG + " 캠페인");
		for (Long campaignId : campaigns) {
			jdbc.update("DELETE FROM send_log WHERE step_id IN (SELECT step_id FROM workflow_step WHERE campaign_id = ?)", campaignId);
			jdbc.update("DELETE FROM workflow_instance WHERE campaign_id = ?", campaignId);
			jdbc.update("DELETE FROM workflow_step WHERE campaign_id = ?", campaignId);
			jdbc.update("DELETE FROM campaign WHERE campaign_id = ?", campaignId);
		}
		jdbc.update("DELETE FROM send_log WHERE customer_id IN (SELECT customer_id FROM customer WHERE email LIKE 'wfload-%@wfload.withus.local')");
		jdbc.update("DELETE FROM segment_rule WHERE segment_id IN (SELECT segment_id FROM segment WHERE name = ?)", TAG + " 대상");
		jdbc.update("DELETE FROM segment WHERE name = ?", TAG + " 대상");
		jdbc.update("DELETE FROM template WHERE name = ?", TAG + " 비광고 메일");
		jdbc.update("DELETE FROM customer WHERE email LIKE 'wfload-%@wfload.withus.local'");
		jdbc.update("DELETE FROM member WHERE email = 'wfload@wfload.withus.local'");
	}
}
