package com.withus.load;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;

import com.withus.campaign.domain.SendKind;
import com.withus.campaign.service.SendQueueService;
import com.withus.common.domain.Channel;

/**
 * 부하 2/3 — 발송 큐 10만 건이 쌓인 상태의 우선순위·스케줄러 동시 실행·적재 시간·메모리 측정 (PRD 10.3 부하 항목).
 * 일반 테스트 묶음에 넣지 않으려고 이름을 *Check 로 둔다. 시간은 단언하지 않고 출력만 한다(우선순위 역전 건수만 단언).
 *
 * 준비: 로컬 DB 에 load-data.sql 로 10만 건을 먼저 만들고(src/test/resources/load/), SMTP 수신기를 띄운다.
 *       Mailpit(1025)을 그대로 쓰면 수천 통이 쌓이므로, 버리는 수신기를 다른 포트로 띄우고 -Dspring.mail.port 로 돌리는 편이 낫다.
 * 실행: ./mvnw test -Dtest=LoadQueueCheck -Dses.max-send-rate=14 [-Dspring.mail.port=1026]
 * 끝나고 load-clean.sql 로 정리한다. 이 클래스가 만드는 캠페인도 이름이 [LOAD] 로 시작해 함께 지워진다.
 *
 * 실제 커밋·실제 스케줄러(@Scheduled 8종)를 쓰므로 롤백 트랜잭션이 아니다. local 이 아닌 DB 에서 돌리면 실제 발송이
 * 시작될 수 있다(PR #71 리뷰). 스케줄러는 Spring 컨텍스트가 뜨는 순간 돌기 시작하므로, 메서드 안의 단언만으로는 늦다 —
 * 아래 JUnit 조건이 컨텍스트를 만들기 전에 환경변수로 막고(건너뜀), 메서드 안의 단언은 -D 옵션 등으로 바뀐 경우를 한 번 더 막는다.
 */
@DisabledIfEnvironmentVariable(named = "DB_URL", matches = "(?!.*//(localhost|127[.]0[.]0[.]1)[:/]).*",
	disabledReason = "DB_URL 이 로컬 DB(localhost·127.0.0.1)가 아니면 실행하지 않는다 — 실제 스케줄러가 돈다")
@DisabledIfEnvironmentVariable(named = "SPRING_PROFILES_ACTIVE", matches = "(?!.*local).+",
	disabledReason = "SPRING_PROFILES_ACTIVE 가 local 이 아니면 실행하지 않는다")
@SpringBootTest
class LoadQueueCheck {

	private static final String LOAD_CAMPAIGN = "[LOAD] 부하 캠페인";

	@Autowired
	Environment environment;
	@Value("${spring.datasource.url}")
	String datasourceUrl;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	SendQueueService sendQueueService;
	@Value("${ses.max-send-rate}")
	int sendRate;

	private volatile boolean sampling;
	private volatile long peakHeapBytes;

	@Test
	void 대기_십만건_상태의_적재_우선순위_스케줄러() throws Exception {
		// 실제 스케줄러가 돌고 캠페인을 ACTIVE 로 바꾸므로, 운영·공용 DB 에서는 절대 실행하지 않는다
		assertThat(environment.acceptsProfiles(Profiles.of("local"))).as("local 프로필에서만 실행한다").isTrue();
		assertThat(datasourceUrl).as("로컬 DB(localhost·127.0.0.1)에서만 실행한다").containsAnyOf("//localhost", "//127.0.0.1");
		Long campaignId = jdbc.queryForObject("SELECT campaign_id FROM campaign WHERE name = ?", Long.class, LOAD_CAMPAIGN);
		assertThat(campaignId).as("먼저 load-data.sql 로 부하 데이터를 만드세요").isNotNull();
		List<Long> customerIds = jdbc.queryForList(
			"SELECT customer_id FROM customer WHERE email LIKE 'load-%@load.withus.local' ORDER BY customer_id", Long.class);
		int n = customerIds.size();
		out("전제: 고객 %d명, 발송 속도 ses.max-send-rate=%d/초, JVM 최대 힙 %dMB", n, sendRate,
			Runtime.getRuntime().maxMemory() / MB);

		// ── ③ 적재 시간·메모리: 디스패처가 끼지 않도록 캠페인을 PAUSED 로 두고(claimBatch 가 제외) 측정한다
		jdbc.update("UPDATE campaign SET status = 'PAUSED' WHERE campaign_id = ?", campaignId);
		jdbc.update("DELETE FROM send_log WHERE campaign_id = ?", campaignId);
		System.gc();
		long baseline = usedHeap();
		startHeapSampler();
		long t0 = System.nanoTime();
		int inserted = sendQueueService.enqueueOneTime(campaignId, customerIds, Channel.EMAIL, SendKind.CAMPAIGN);
		long enqueueMs = (System.nanoTime() - t0) / 1_000_000;
		stopHeapSampler();
		out("③ 적재 %d건: %.1f초 (%.0f건/초), 힙 최대 %dMB (적재 전 GC 후 %dMB)", inserted, enqueueMs / 1000.0,
			inserted * 1000.0 / enqueueMs, peakHeapBytes / MB, baseline / MB);
		assertThat(inserted).isEqualTo(n);

		// ── ① ② 용 픽스처: 워크플로우(TRIGGER → SEND_EMAIL → END) 와 비교용 캠페인
		Long templateId = jdbc.queryForObject("SELECT template_id FROM campaign WHERE campaign_id = ?", Long.class, campaignId);
		Long segmentId = jdbc.queryForObject("SELECT segment_id FROM campaign WHERE campaign_id = ?", Long.class, campaignId);
		Long memberId = jdbc.queryForObject("SELECT member_id FROM member WHERE email = 'load-owner@load.withus.local'", Long.class);
		long wfCampaign = insertReturningId(
			"INSERT INTO campaign (name, type, status, segment_id, trigger_type, started_at, created_by) "
				+ "VALUES ('[LOAD] 환영 워크플로우', 'WORKFLOW', 'ACTIVE', ?, 'CUSTOMER_REGISTERED', now(), ?) RETURNING campaign_id",
			segmentId, memberId);
		long endStep = insertReturningId("INSERT INTO workflow_step (campaign_id, node_type) VALUES (?, 'END') RETURNING step_id", wfCampaign);
		long sendStep = insertReturningId("INSERT INTO workflow_step (campaign_id, node_type, config_json, next_step_id) "
			+ "VALUES (?, 'SEND_EMAIL', jsonb_build_object('templateId', ?), ?) RETURNING step_id", wfCampaign, templateId, endStep);
		jdbc.update("INSERT INTO workflow_step (campaign_id, node_type, config_json, next_step_id) "
			+ "VALUES (?, 'TRIGGER', '{\"triggerType\":\"CUSTOMER_REGISTERED\"}'::jsonb, ?)", wfCampaign, sendStep);
		long welcomeCustomer = customerIds.get(0);
		long welcomeInstance = insertReturningId(
			"INSERT INTO workflow_instance (campaign_id, customer_id, current_step_id, status, next_run_at) "
				+ "VALUES (?, ?, ?, 'WAITING', now()) RETURNING instance_id", wfCampaign, welcomeCustomer, sendStep);

		// ② 용: 멈춘 SENDING 1건, 멈춘 RUNNING 인스턴스 1건, 이미 끝난 일회성 캠페인 1건 — 각 스케줄 작업이 제때 처리하는지 본다
		long stuckSendLog = jdbc.queryForObject("SELECT max(send_log_id) FROM send_log WHERE campaign_id = ?", Long.class, campaignId);
		jdbc.update("UPDATE send_log SET status = 'SENDING', updated_at = now() - interval '20 minutes' WHERE send_log_id = ?", stuckSendLog);
		long stuckInstance = insertReturningId(
			"INSERT INTO workflow_instance (campaign_id, customer_id, current_step_id, status, updated_at) "
				+ "VALUES (?, ?, ?, 'RUNNING', now() - interval '20 minutes') RETURNING instance_id",
			wfCampaign, customerIds.get(1), sendStep);
		long finishedCampaign = insertReturningId(
			"INSERT INTO campaign (name, type, status, segment_id, template_id, started_at, created_by) "
				+ "VALUES ('[LOAD] 완료 확인', 'ONE_TIME', 'ACTIVE', ?, ?, now() - interval '1 hour', ?) RETURNING campaign_id",
			segmentId, templateId, memberId);

		// ── 디스패처 가동: 캠페인을 ACTIVE 로 되돌리면 10만 건 처리가 시작된다
		long sentBefore = count("SELECT count(*) FROM send_log WHERE campaign_id = ? AND status = 'SENT'", campaignId);
		startHeapSampler();
		long start = System.nanoTime();
		jdbc.update("UPDATE campaign SET status = 'ACTIVE' WHERE campaign_id = ?", campaignId);

		long[] doneMs = { -1, -1, -1 }; // 복구 SENDING, 복구 RUNNING, 자동 완료
		waitUntil(Duration.ofSeconds(240), () -> {
			long now = (System.nanoTime() - start) / 1_000_000;
			if (doneMs[0] < 0 && "FAILED".equals(status("send_log", "send_log_id", stuckSendLog))) doneMs[0] = now;
			if (doneMs[1] < 0 && !"RUNNING".equals(status("workflow_instance", "instance_id", stuckInstance))) doneMs[1] = now;
			if (doneMs[2] < 0 && "COMPLETED".equals(status("campaign", "campaign_id", finishedCampaign))) doneMs[2] = now;
			return doneMs[0] >= 0 && doneMs[1] >= 0 && doneMs[2] >= 0 && welcomeSent(welcomeInstance);
		});
		stopHeapSampler();
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;
		long sentWhile = count("SELECT count(*) FROM send_log WHERE campaign_id = ? AND status = 'SENT'", campaignId) - sentBefore;

		// ① 우선순위: 환영 메일(priority 2)이 적재된 시각, 발송된 시각, 그 사이에 priority 3 이 몇 건 앞질러 나갔는지
		Map<String, Object> welcome = jdbc.queryForMap(
			"SELECT send_log_id, priority, status, created_at, sent_at, extract(epoch FROM sent_at - created_at) AS wait_sec "
				+ "FROM send_log WHERE instance_id = ? AND step_id = ?", welcomeInstance, sendStep);
		long overtaken = count("SELECT count(*) FROM send_log WHERE campaign_id = ? AND status = 'SENT' AND sent_at > ? AND sent_at < ?",
			campaignId, welcome.get("created_at"), welcome.get("sent_at"));
		long pendingLeft = count("SELECT count(*) FROM send_log WHERE campaign_id = ? AND status = 'PENDING'", campaignId);

		out("① 환영 메일(priority 2): 적재→발송 %.1f초, 그 사이 priority 3 이 앞질러 나간 건수 %d (대기 중 priority 3 은 %d건 남음)",
			((Number) welcome.get("wait_sec")).doubleValue(), overtaken, pendingLeft);
		out("② 10만 건 처리 중 스케줄 작업: 멈춘 SENDING 복구 %s, 멈춘 RUNNING 복구 %s, 일회성 자동 완료 %s (각 작업 주기 60초)",
			sec(doneMs[0]), sec(doneMs[1]), sec(doneMs[2]));
		out("   같은 %.0f초 동안 priority 3 발송 %d건 (%.2f건/초), 힙 최대 %dMB", elapsedMs / 1000.0, sentWhile,
			sentWhile * 1000.0 / elapsedMs, peakHeapBytes / MB);

		assertThat(welcome.get("status")).isEqualTo("SENT");
		// 이미 선점(SENDING)된 한 묶음(claimBatch LIMIT 50)은 priority 와 무관하게 끝까지 나간다 — 그 이상 앞지르면 우선순위가 깨진 것
		assertThat(overtaken).as("선점 묶음(50건)보다 많이 앞질렀다").isLessThanOrEqualTo(50);
		assertThat(doneMs).as("스케줄 작업 3종이 모두 처리돼야 한다").doesNotContain(-1L);
	}

	private boolean welcomeSent(long instanceId) {
		Long sent = jdbc.queryForObject("SELECT count(*) FROM send_log WHERE instance_id = ? AND status = 'SENT'", Long.class, instanceId);
		return sent != null && sent > 0;
	}

	private String status(String table, String idColumn, long id) {
		return jdbc.queryForObject("SELECT status FROM " + table + " WHERE " + idColumn + " = ?", String.class, id);
	}

	private long count(String sql, Object... args) {
		Long value = jdbc.queryForObject(sql, Long.class, args);
		return value == null ? 0 : value;
	}

	private long insertReturningId(String sql, Object... args) {
		return jdbc.queryForObject(sql, Long.class, args);
	}

	private void waitUntil(Duration timeout, BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			Thread.sleep(500);
		}
	}

	private static final long MB = 1024 * 1024;

	private static long usedHeap() {
		return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
	}

	private void startHeapSampler() {
		sampling = true;
		peakHeapBytes = 0;
		Thread sampler = new Thread(() -> {
			while (sampling) {
				peakHeapBytes = Math.max(peakHeapBytes, usedHeap());
				try {
					Thread.sleep(50);
				} catch (InterruptedException e) {
					return;
				}
			}
		}, "heap-sampler");
		sampler.setDaemon(true);
		sampler.start();
	}

	private void stopHeapSampler() {
		sampling = false;
	}

	private static String sec(long ms) {
		return ms < 0 ? "미처리(시간 초과)" : String.format("%.1f초", ms / 1000.0);
	}

	private static void out(String format, Object... args) {
		System.out.println("[LOAD] " + String.format(format, args));
	}
}
