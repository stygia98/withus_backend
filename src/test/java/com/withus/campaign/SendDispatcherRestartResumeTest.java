package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.withus.auth.domain.Member;
import com.withus.auth.domain.Role;
import com.withus.auth.mapper.MemberMapper;
import com.withus.campaign.domain.SendKind;
import com.withus.campaign.domain.SendLog;
import com.withus.campaign.domain.SendStatus;
import com.withus.campaign.service.SendDispatcher;
import com.withus.campaign.service.SendQueueService;
import com.withus.campaign.service.SendRecoveryJob;
import com.withus.common.domain.Channel;

/**
 * PRD 10.3 "발송 도중 서버를 재시작해도 남은 건부터 이어서 발송된다"와 "처리 도중 서버를 강제 종료해도 … SENDING 건은 다시
 * 나가지 않는다"를 한 시나리오로 확인한다 (발송 큐 Plan 13장). 프로세스를 실제로 죽일 수는 없어서, 죽는 순간의 DB 상태를
 * 그대로 만들어 두고 "재기동 뒤 첫 주기"(복구 작업 + 디스패처)를 돌려 결과를 본다.
 *
 * <p>강제 종료 시점의 상태 — 디스패처는 한 번에 최대 50건을 선점(SENDING 커밋)한 뒤 건별로 발송한다. 그 50건 중 20건만
 * 보내고 죽으면: 20건 SENT, 30건 SENDING(보냈는지 모름), 나머지 70건은 아직 PENDING 이다.
 * 재기동 후에는 ① 이미 보낸 20건은 다시 나가지 않고 ② SENDING 으로 10분 넘게 남은 30건은 재발송하지 않고
 * FAILED(UNKNOWN_RESULT)로 끝나며(중복보다 누락, CLAUDE.md 6장 5번) ③ PENDING 70건은 이어서 모두 발송돼야 한다.
 * 선점(SENDING 커밋)은 SQL 로 만들고 발송은 실제 processOne 으로 하므로, 다른 캠페인의 잔여 PENDING 건에 영향을 받지 않는다.
 * 로컬 Docker DB + Mailpit 이 떠 있어야 한다. 디스패처는 자체 커밋 트랜잭션을 쓰므로 @Transactional 없이 직접 정리한다
 */
@SpringBootTest(properties = {
	"withus.scheduler.send-dispatcher.enabled=false",
	"withus.scheduler.send-recovery.enabled=false",
	"ses.max-send-rate=1000" // 120건 시나리오가 초당 1건 제한(기본)으로 2분 걸리지 않게 한다
})
class SendDispatcherRestartResumeTest {

	private static final int TOTAL = 120;
	private static final int CLAIMED = 50; // 디스패처가 한 번에 선점하는 묶음(claimBatch LIMIT)
	private static final int SENT_BEFORE_CRASH = 20;

	@Autowired
	SendDispatcher sendDispatcher;
	@Autowired
	SendRecoveryJob sendRecoveryJob;
	@Autowired
	SendQueueService sendQueueService;
	@Autowired
	MemberMapper memberMapper;
	@Autowired
	JdbcTemplate jdbcTemplate;

	Long memberId;
	Long segmentId;
	Long campaignId;
	Long templateId;
	List<Long> customerIds = new ArrayList<>();

	@BeforeEach
	void setUp() {
		Member member = new Member();
		member.setEmail("resume-" + UUID.randomUUID() + "@withus.local");
		member.setPassword("x");
		member.setName("재시작 테스트");
		member.setRole(Role.MANAGER);
		memberMapper.insert(member);
		memberId = member.getMemberId();

		// 비광고 템플릿 — 시간창(08:00~20:50)을 적용받지 않아 실행 시각과 무관하게 나간다
		jdbcTemplate.update("INSERT INTO template (channel, name, subject, body, ad_yn, created_by) "
			+ "VALUES ('EMAIL', ?, '제목', '본문', 'N', ?)", "재시작 템플릿", memberId);
		templateId = jdbcTemplate.queryForObject("SELECT max(template_id) FROM template", Long.class);
		jdbcTemplate.update("INSERT INTO segment (name, created_by) VALUES (?, ?)", "재시작 세그먼트", memberId);
		segmentId = jdbcTemplate.queryForObject("SELECT max(segment_id) FROM segment", Long.class);
		jdbcTemplate.update("INSERT INTO campaign (name, type, status, segment_id, template_id, created_by) "
			+ "VALUES (?, 'ONE_TIME', 'ACTIVE', ?, ?, ?)", "재시작 캠페인", segmentId, templateId, memberId);
		campaignId = jdbcTemplate.queryForObject("SELECT max(campaign_id) FROM campaign", Long.class);

		String tag = UUID.randomUUID().toString();
		jdbcTemplate.update("INSERT INTO customer (email, joined_at, source, email_consent_yn) "
			+ "SELECT 'resume-' || ? || '-' || i || '@withus.local', now(), 'MANUAL', 'Y' FROM generate_series(1, ?) AS i",
			tag, TOTAL);
		customerIds = jdbcTemplate.queryForList(
			"SELECT customer_id FROM customer WHERE email LIKE ? ORDER BY customer_id", Long.class,
			"resume-" + tag + "-%@withus.local");
	}

	@AfterEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM send_log WHERE campaign_id = ?", campaignId);
		jdbcTemplate.update("DELETE FROM campaign WHERE campaign_id = ?", campaignId);
		jdbcTemplate.update("DELETE FROM segment WHERE segment_id = ?", segmentId);
		jdbcTemplate.update("DELETE FROM template WHERE template_id = ?", templateId);
		for (Long customerId : customerIds) {
			jdbcTemplate.update("DELETE FROM customer WHERE customer_id = ?", customerId);
		}
		jdbcTemplate.update("DELETE FROM member WHERE member_id = ?", memberId);
	}

	@Test
	void 강제_종료_뒤_재기동하면_남은_PENDING만_이어서_나가고_보낸_건과_SENDING_건은_다시_나가지_않는다() {
		assertThat(sendQueueService.enqueueOneTime(campaignId, customerIds, Channel.EMAIL, SendKind.CAMPAIGN))
			.isEqualTo(TOTAL);
		List<Long> ids = jdbcTemplate.queryForList(
			"SELECT send_log_id FROM send_log WHERE campaign_id = ? ORDER BY send_log_id", Long.class, campaignId);
		assertThat(ids).hasSize(TOTAL);

		// ── 서버 1차 가동: 50건을 선점(SENDING 커밋)하고 그중 20건만 실제로 발송한 뒤 강제 종료 ──
		List<Long> claimed = ids.subList(0, CLAIMED);
		jdbcTemplate.update("UPDATE send_log SET status = 'SENDING' WHERE send_log_id IN (" + join(claimed) + ")");
		List<Long> sentBeforeCrash = claimed.subList(0, SENT_BEFORE_CRASH);
		List<Long> inFlight = claimed.subList(SENT_BEFORE_CRASH, CLAIMED);
		List<Long> neverClaimed = ids.subList(CLAIMED, TOTAL);
		for (Long sendLogId : sentBeforeCrash) {
			sendDispatcher.processOne(sendLogOf(sendLogId));
		}
		Map<Long, String> providerIdsBeforeRestart = providerIds(sentBeforeCrash);
		assertThat(providerIdsBeforeRestart.values()).as("강제 종료 전에 보낸 건").doesNotContainNull().hasSize(SENT_BEFORE_CRASH);
		assertThat(statusCounts()).containsEntry("SENT", (long) SENT_BEFORE_CRASH).containsEntry("SENDING", (long) inFlight.size())
			.containsEntry("PENDING", (long) neverClaimed.size());

		// ── 시간이 지나 SENDING 건이 10분을 넘긴다(보냈는지 알 수 없는 건) ──
		jdbcTemplate.update("UPDATE send_log SET updated_at = now() - interval '11 minutes' WHERE send_log_id IN ("
			+ join(inFlight) + ")");

		// ── 재기동 후 첫 주기: 멈춤 복구 작업 → 디스패처가 남은 PENDING 을 소진할 때까지 ──
		sendRecoveryJob.recover();
		for (int guard = 0; guard < 10 && count("PENDING") > 0; guard++) {
			sendDispatcher.dispatch();
		}

		// ① 이미 보낸 20건은 그대로다(다시 나가지 않았다: 상태·발송 식별자 불변)
		assertThat(providerIds(sentBeforeCrash)).as("이미 보낸 건의 provider_message_id 는 바뀌지 않는다")
			.isEqualTo(providerIdsBeforeRestart);
		assertThat(statusesOf(sentBeforeCrash)).containsOnly("SENT");
		// ② SENDING 으로 남았던 30건은 재발송하지 않고 FAILED(UNKNOWN_RESULT) — 보낸 적이 없으니 발송 식별자도 없다
		assertThat(statusesOf(inFlight)).containsOnly("FAILED");
		assertThat(providerIds(inFlight).values()).containsOnlyNulls();
		assertThat(jdbcTemplate.queryForList("SELECT DISTINCT error_message FROM send_log WHERE send_log_id IN ("
			+ join(inFlight) + ")", String.class)).containsExactly("UNKNOWN_RESULT");
		// ③ 한 번도 선점되지 않았던 70건은 이어서 모두 발송됐다
		assertThat(statusesOf(neverClaimed)).containsOnly("SENT");
		assertThat(providerIds(neverClaimed).values()).doesNotContainNull();
		// 합계: 보낸 20 + 이어서 70 = 90, 누락 처리(UNKNOWN_RESULT) 30, 더 남은 것 없음
		assertThat(statusCounts()).containsEntry("SENT", 90L).containsEntry("FAILED", 30L)
			.doesNotContainKeys("PENDING", "SENDING");
	}

	/** processOne 에 넘길 선점된 건. claimBatch 가 돌려주는 것과 같은 필드를 DB 에서 읽어 만든다 */
	private SendLog sendLogOf(long sendLogId) {
		return jdbcTemplate.queryForObject("SELECT send_log_id, customer_id, recipient FROM send_log WHERE send_log_id = ?",
			(rs, rowNum) -> SendLog.builder().sendLogId(rs.getLong("send_log_id")).campaignId(campaignId)
				.customerId(rs.getLong("customer_id")).recipient(rs.getString("recipient")).channel(Channel.EMAIL)
				.status(SendStatus.SENDING).kind(SendKind.CAMPAIGN).priority(SendLog.PRIORITY_CAMPAIGN_BULK).build(),
			sendLogId);
	}

	private Map<Long, String> providerIds(List<Long> sendLogIds) {
		Map<Long, String> result = new HashMap<>();
		jdbcTemplate.query("SELECT send_log_id, provider_message_id FROM send_log WHERE send_log_id IN (" + join(sendLogIds) + ")",
			rs -> {
				result.put(rs.getLong("send_log_id"), rs.getString("provider_message_id"));
			});
		return result;
	}

	private List<String> statusesOf(List<Long> sendLogIds) {
		return jdbcTemplate.queryForList("SELECT status FROM send_log WHERE send_log_id IN (" + join(sendLogIds) + ")",
			String.class);
	}

	private Map<String, Long> statusCounts() {
		Map<String, Long> result = new HashMap<>();
		jdbcTemplate.query("SELECT status, count(*) AS n FROM send_log WHERE campaign_id = ? GROUP BY status",
			rs -> {
				result.put(rs.getString("status"), rs.getLong("n"));
			}, campaignId);
		return result;
	}

	private long count(String status) {
		return statusCounts().getOrDefault(status, 0L);
	}

	/** 숫자 ID 목록만 SQL 에 넣는다(테스트 안에서 만든 값이라 주입 위험 없음) */
	private static String join(List<Long> ids) {
		return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
	}
}
