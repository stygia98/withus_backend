package com.withus.load;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.withus.campaign.domain.CustomerRecipient;
import com.withus.campaign.domain.SendKind;
import com.withus.campaign.domain.SendLog;
import com.withus.campaign.domain.SendStatus;
import com.withus.campaign.mapper.SendLogMapper;
import com.withus.common.domain.Channel;
import com.withus.customer.service.ConsentService;

/**
 * 이슈 #73 — 캠페인 시작 적재(SendQueueService.enqueueOneTime)가 청크(500건)당 어디에서 시간을 쓰는지 단계별로 잰다.
 * 운영 코드는 바꾸지 않고, 같은 mapper·ConsentService 호출을 같은 순서로 따라 하며 단계마다 시간을 재 합산한다.
 * 단계: ① 수신처 조회(findRecipients) ② 동의 확인(filterSendable) ③ INSERT ④ 커밋. 그리고 왕복 기준선(SELECT 1).
 * 시간은 단언하지 않고 출력만 한다(건수만 단언). 일반 테스트 묶음에 넣지 않으려고 이름을 *Check 로 둔다.
 *
 * 준비: load-data.sql 로 고객 10만 건을 만든다(src/test/resources/load/). 실행:
 * ./mvnw test -Dtest=EnqueuePhaseCheck -Dwithus.scheduler.send-dispatcher.enabled=false
 * 끝나고 load-clean.sql 로 정리한다(이 클래스가 만든 send_log 는 끝에 직접 지운다).
 * 같은 환경(로컬/PL Docker)에서 돌려 단계별 비율을 비교하면 어디가 환경에 민감한지 알 수 있다.
 *
 * 실제 커밋을 하므로 로컬 DB 에서만 실행한다(LoadQueueCheck 와 같은 가드).
 *
 * 꺼 두는 스케줄러는 send-dispatcher 하나뿐이다. 캠페인 예약·완료·복구 등 나머지 스케줄러는 계속 돌지만, 대상 캠페인을
 * PAUSED 로 두고 측정하므로(claimBatch 와 자동 완료 모두 PAUSED 를 건드리지 않는다) 측정에 영향이 없다.
 */
@DisabledIfEnvironmentVariable(named = "DB_URL", matches = "(?!.*//(localhost|127[.]0[.]0[.]1)[:/]).*",
	disabledReason = "DB_URL 이 로컬 DB(localhost·127.0.0.1)가 아니면 실행하지 않는다")
@DisabledIfEnvironmentVariable(named = "SPRING_PROFILES_ACTIVE", matches = "(?!.*local).+",
	disabledReason = "SPRING_PROFILES_ACTIVE 가 local 이 아니면 실행하지 않는다")
@SpringBootTest(properties = "withus.scheduler.send-dispatcher.enabled=false")
class EnqueuePhaseCheck {

	private static final String LOAD_CAMPAIGN = "[LOAD] 부하 캠페인";
	private static final int CHUNK = 500; // SendQueueService.BATCH_SIZE 와 같게
	private static final int PING_COUNT = 200;

	@Autowired
	Environment environment;
	@Value("${spring.datasource.url}")
	String datasourceUrl;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	SendLogMapper sendLogMapper;
	@Autowired
	ConsentService consentService;
	@Autowired
	PlatformTransactionManager transactionManager;

	@Test
	void 청크당_단계별_시간() {
		// 실제 커밋을 하므로, 환경변수 없이 -D 옵션 등으로 바뀐 경우를 한 번 더 막는다(LoadQueueCheck 와 같은 2차 방어선)
		assertThat(environment.acceptsProfiles(Profiles.of("local"))).as("local 프로필에서만 실행한다").isTrue();
		assertThat(datasourceUrl).as("로컬 DB(localhost·127.0.0.1)에서만 실행한다").containsAnyOf("//localhost", "//127.0.0.1");
		// queryForObject 는 0건이면 EmptyResultDataAccessException 을 던져 아래 안내가 안 보이므로 목록으로 읽는다
		List<Long> campaignIds = jdbc.queryForList("SELECT campaign_id FROM campaign WHERE name = ?", Long.class, LOAD_CAMPAIGN);
		assertThat(campaignIds).as("먼저 load-data.sql 로 부하 데이터를 만드세요").isNotEmpty();
		Long campaignId = campaignIds.get(0);
		List<Long> customerIds = jdbc.queryForList(
			"SELECT customer_id FROM customer WHERE email LIKE 'load-%@load.withus.local' ORDER BY customer_id", Long.class);
		// 디스패처가 끼지 않도록 PAUSED, 이전 측정분은 지운다
		jdbc.update("UPDATE campaign SET status = 'PAUSED' WHERE campaign_id = ?", campaignId);
		jdbc.update("DELETE FROM send_log WHERE campaign_id = ?", campaignId);

		// 기준선: 아무 일도 안 하는 쿼리의 왕복 시간 — 환경의 순수 왕복 비용
		jdbc.queryForObject("SELECT 1", Integer.class); // 연결 확보
		long p0 = System.nanoTime();
		for (int i = 0; i < PING_COUNT; i++) {
			jdbc.queryForObject("SELECT 1", Integer.class);
		}
		double pingMs = (System.nanoTime() - p0) / 1e6 / PING_COUNT;

		double[] walBefore = settledWalStats();
		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		int chunks = 0;
		long recipientsNs = 0, consentNs = 0, insertNs = 0, commitNs = 0, buildNs = 0;
		int inserted = 0;
		long wall0 = System.nanoTime();
		for (int from = 0; from < customerIds.size(); from += CHUNK) {
			List<Long> chunk = customerIds.subList(from, Math.min(from + CHUNK, customerIds.size()));
			long[] t = new long[6]; // [0]시작 [1]조회후 [2]동의후 [3]빌드후 [4]INSERT후 [5]커밋후
			int[] rows = new int[1];
			tx.executeWithoutResult(status -> {
				t[0] = System.nanoTime();
				List<CustomerRecipient> found = sendLogMapper.findRecipients(chunk, Channel.EMAIL);
				t[1] = System.nanoTime();
				Set<Long> sendable = consentService.filterSendable(
					found.stream().map(CustomerRecipient::getCustomerId).collect(Collectors.toList()), Channel.EMAIL);
				t[2] = System.nanoTime();
				List<SendLog> logs = new ArrayList<>();
				for (CustomerRecipient r : found) {
					boolean ok = sendable.contains(r.getCustomerId());
					logs.add(SendLog.builder().campaignId(campaignId).customerId(r.getCustomerId())
						.recipient(r.getRecipient()).channel(Channel.EMAIL)
						.status(ok ? SendStatus.PENDING : SendStatus.SKIPPED).kind(SendKind.CAMPAIGN)
						.priority(SendLog.PRIORITY_CAMPAIGN_BULK).errorMessage(ok ? null : "NOT_SENDABLE").build());
				}
				t[3] = System.nanoTime();
				rows[0] = sendLogMapper.insertOneTimeBatch(logs);
				t[4] = System.nanoTime();
			});
			t[5] = System.nanoTime(); // executeWithoutResult 가 돌아온 시각 = 커밋 끝
			recipientsNs += t[1] - t[0];
			consentNs += t[2] - t[1];
			buildNs += t[3] - t[2];
			insertNs += t[4] - t[3];
			commitNs += t[5] - t[4];
			inserted += rows[0];
			chunks++;
		}
		double wallSec = (System.nanoTime() - wall0) / 1e9;
		double[] walAfter = settledWalStats(); // wallSec 를 잰 뒤라 대기 시간이 총 시간에 섞이지 않는다

		out("전제: 고객 %d명, 청크 %d개(500건), 왕복 기준선 SELECT 1 = %.2fms", customerIds.size(), chunks, pingMs);
		out("총 %.1f초 (%.0f건/초), 적재 %d건", wallSec, inserted / wallSec, inserted);
		out("청크당 평균(ms): ① 수신처 조회 %.1f · ② 동의 확인 %.1f · ③ INSERT %.1f · ④ 커밋 %.1f · (자바 조립 %.1f)",
			ms(recipientsNs, chunks), ms(consentNs, chunks), ms(insertNs, chunks), ms(commitNs, chunks), ms(buildNs, chunks));
		out("비율: ① %.0f%% · ② %.0f%% · ③ %.0f%% · ④ %.0f%%", pct(recipientsNs, wallSec), pct(consentNs, wallSec),
			pct(insertNs, wallSec), pct(commitNs, wallSec));
		reportWal(walBefore, walAfter, wallSec);
		// 서버 쪽 시간과 비교하려고 INSERT 한 번을 EXPLAIN ANALYZE 로도 남긴다(롤백)
		jdbc.update("DELETE FROM send_log WHERE campaign_id = ?", campaignId); // 유니크 충돌을 피하려고 먼저 비운다
		explainInsert(campaignId, customerIds.subList(0, Math.min(CHUNK, customerIds.size())));

		jdbc.update("DELETE FROM send_log WHERE campaign_id = ?", campaignId);
		assertThat(inserted).isEqualTo(customerIds.size());
	}

	/**
	 * pg_stat_wal 누적값 {fsync 횟수, fsync 시간(ms), WAL 바이트, 레코드 수, 쓰기 횟수, 쓰기 시간(ms)}. 읽을 수 없으면 null.
	 * PG17 까지는 wal_sync·wal_sync_time 컬럼이 있고, 그 뒤 버전은 pg_stat_io 로 옮겨졌을 수 있어 그때는 null 을 돌려준다.
	 * 통계는 최대 약 1초 늦게 공유 메모리에 반영되므로 읽기 전에 잠시 기다린다(이슈 #73: 병목이 WAL fsync 였다).
	 * 값은 DB 전체 누적이라 측정 중 다른 세션이 쓴 WAL 도 섞인다 — 조용한 DB 에서 잰다.
	 */
	private double[] settledWalStats() {
		try {
			Thread.sleep(1500);
			return jdbc.queryForObject(
				"SELECT wal_sync, wal_sync_time, wal_bytes, wal_records, wal_write, wal_write_time FROM pg_stat_wal",
				(rs, i) -> new double[] { rs.getDouble(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4),
					rs.getDouble(5), rs.getDouble(6) });
		} catch (DataAccessException e) {
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}

	private static void reportWal(double[] before, double[] after, double wallSec) {
		if (before == null || after == null) {
			out("WAL: pg_stat_wal 의 wal_sync·wal_sync_time 을 읽을 수 없다(PG18 이상은 pg_stat_io 의 object = 'wal' 을 본다)");
			return;
		}
		double syncs = after[0] - before[0];
		double syncSec = (after[1] - before[1]) / 1000.0;
		double writes = after[4] - before[4];
		double writeSec = (after[5] - before[5]) / 1000.0;
		out("WAL: 생성 %.0fMB, 레코드 %.0f건, 쓰기 %.0f회, fsync %.0f회", (after[2] - before[2]) / (1024 * 1024),
			after[3] - before[3], writes, syncs);
		if (syncs == 0) {
			// wal_sync 는 wal_sync_method 가 fdatasync·fsync 일 때만 센다(Linux 기본, RDS 도 해당).
			// open_datasync 같은 방식(Windows 기본)은 쓰기와 동기화가 한 번에 일어나 wal_write_time 에 합쳐진다
			out("WAL: fsync 가 0회 — wal_sync_method 가 fdatasync·fsync 가 아니면(예: Windows open_datasync) 동기 쓰기 시간이 쓰기 시간에 포함된다");
		}
		if ((syncs > 0 || writes > 0) && syncSec == 0 && writeSec == 0) {
			out("WAL: 시간이 0 — track_wal_io_timing 이 꺼져 있으면 기록되지 않는다"
				+ "(로컬: ALTER SYSTEM SET track_wal_io_timing = on; SELECT pg_reload_conf(); RDS: 파라미터 그룹에서 켠다)");
			return;
		}
		out("WAL: fsync 시간 %.1f초 (총 시간의 %.0f%%), 회당 %.1fms / 쓰기 시간 %.1f초 (총 시간의 %.0f%%)", syncSec,
			syncSec / wallSec * 100, syncs == 0 ? 0 : syncSec * 1000 / syncs, writeSec, writeSec / wallSec * 100);
	}

	/** 같은 형태의 500건 INSERT 를 서버에서 실제로 얼마에 실행하는지(플래닝·실행·트리거) — 클라이언트 측정과의 차이가 네트워크·드라이버 비용 */
	private void explainInsert(Long campaignId, List<Long> ids) {
		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		tx.executeWithoutResult(status -> {
			List<String> plan = jdbc.queryForList(
				"EXPLAIN (ANALYZE, BUFFERS) INSERT INTO send_log (campaign_id, customer_id, recipient, channel, status, kind, priority) "
					+ "SELECT ?, c.customer_id, c.email, 'EMAIL', 'PENDING', 'CAMPAIGN', 3 FROM customer c WHERE c.customer_id = ANY (?)",
				String.class, campaignId, ids.toArray(new Long[0]));
			plan.stream().filter(l -> l.contains("Execution Time") || l.contains("Planning Time") || l.contains("Trigger"))
				.forEach(l -> out("   서버 측 %s", l.trim()));
			status.setRollbackOnly();
		});
	}

	private static double ms(long ns, int chunks) {
		return ns / 1e6 / chunks;
	}

	private static double pct(long ns, double wallSec) {
		return ns / 1e9 / wallSec * 100;
	}

	private static void out(String fmt, Object... args) {
		System.out.println("[PHASE] " + String.format(fmt, args));
	}
}
