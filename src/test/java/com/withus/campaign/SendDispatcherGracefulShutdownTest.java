package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import com.withus.WithusBackendApplication;
import com.withus.auth.domain.Member;
import com.withus.auth.domain.Role;
import com.withus.auth.mapper.MemberMapper;
import com.withus.campaign.domain.SendKind;
import com.withus.campaign.service.SendDispatcher;
import com.withus.campaign.service.SendQueueService;
import com.withus.common.domain.Channel;

/**
 * 정상 종료(컨텍스트 close) 중에 선점분이 SENDING 으로 남지 않는지 실제 스케줄러로 확인한다 (이슈 #92).
 * 스케줄러가 켜진 별도 애플리케이션 컨텍스트를 띄워 초당 1건으로 발송시키고, 선점 묶음(50건, 50초치)을 다 보내기 전에
 * close() 한다. 고치기 전에는 종료 단계 제한(30초)을 넘겨 DB 풀이 먼저 닫히고 선점분 일부가 SENDING 으로 남았다
 * (60건 중 SENDING 22). 고친 뒤에는 현재 건만 마치고 남은 선점분을 PENDING 으로 되돌려 곧바로 닫혀야 한다.
 * 종료 후 상태는 별도 컨텍스트가 아니라 이 테스트 컨텍스트의 JdbcTemplate(별도 커넥션 풀)으로 본다.
 * 로컬 DB + Mailpit 이 떠 있어야 한다. 디스패처는 자체 커밋 트랜잭션을 쓰므로 @Transactional 없이 직접 정리한다
 */
@SpringBootTest(properties = {
	"withus.scheduler.send-dispatcher.enabled=false",
	"ses.max-send-rate=1000" // 종료 후 남은 건을 이어서 보낼 때 초당 1건 제한으로 1분 걸리지 않게 한다
})
class SendDispatcherGracefulShutdownTest {

	private static final int TOTAL = 60;
	private static final int SENT_BEFORE_CLOSE = 3;

	@Autowired
	SendDispatcher sendDispatcher;
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
	List<Long> customerIds;
	ConfigurableApplicationContext runningApp;

	@BeforeEach
	void setUp() {
		Member member = new Member();
		member.setEmail("shutdown-" + UUID.randomUUID() + "@withus.local");
		member.setPassword("x");
		member.setName("정상 종료 테스트");
		member.setRole(Role.MANAGER);
		memberMapper.insert(member);
		memberId = member.getMemberId();

		// 비광고 템플릿 — 시간창(08:00~20:50)을 적용받지 않아 실행 시각과 무관하게 나간다
		jdbcTemplate.update("INSERT INTO template (channel, name, subject, body, ad_yn, created_by) "
			+ "VALUES ('EMAIL', ?, '제목', '본문', 'N', ?)", "정상 종료 템플릿", memberId);
		templateId = jdbcTemplate.queryForObject("SELECT max(template_id) FROM template", Long.class);
		jdbcTemplate.update("INSERT INTO segment (name, created_by) VALUES (?, ?)", "정상 종료 세그먼트", memberId);
		segmentId = jdbcTemplate.queryForObject("SELECT max(segment_id) FROM segment", Long.class);
		jdbcTemplate.update("INSERT INTO campaign (name, type, status, segment_id, template_id, created_by) "
			+ "VALUES (?, 'ONE_TIME', 'ACTIVE', ?, ?, ?)", "정상 종료 캠페인", segmentId, templateId, memberId);
		campaignId = jdbcTemplate.queryForObject("SELECT max(campaign_id) FROM campaign", Long.class);

		String tag = UUID.randomUUID().toString();
		jdbcTemplate.update("INSERT INTO customer (email, joined_at, source, email_consent_yn) "
			+ "SELECT 'shutdown-' || ? || '-' || i || '@withus.local', now(), 'MANUAL', 'Y' FROM generate_series(1, ?) AS i",
			tag, TOTAL);
		customerIds = jdbcTemplate.queryForList(
			"SELECT customer_id FROM customer WHERE email LIKE ? ORDER BY customer_id", Long.class,
			"shutdown-" + tag + "-%@withus.local");
	}

	@AfterEach
	void cleanUp() {
		if (runningApp != null && runningApp.isActive()) {
			runningApp.close();
		}
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
	void 선점_묶음을_다_보내기_전에_정상_종료해도_SENDING이_남지_않고_재기동하면_이어서_나간다() throws Exception {
		assertThat(sendQueueService.enqueueOneTime(campaignId, customerIds, Channel.EMAIL, SendKind.CAMPAIGN))
			.isEqualTo(TOTAL);

		// ── 서버 가동: 디스패처 스케줄러만 켠다. 캠페인 자동 완료·멈춤 복구 등은 이 시나리오와 무관해 끈다 ──
		// 웹 계층(보안 설정)이 서블릿 환경을 전제하므로 웹은 켜고 포트만 무작위로 둔다(띄워 둔 로컬 서버 8080 과 겹치지 않게)
		runningApp = new SpringApplicationBuilder(WithusBackendApplication.class)
			.properties(
				"server.port=0",
				"ses.max-send-rate=1", // 운영(SES 샌드박스)과 같은 초당 1건 — 선점 50건이 50초치가 된다
				"withus.scheduler.send-dispatcher.enabled=true",
				"withus.scheduler.send-recovery.enabled=false",
				"withus.scheduler.campaign-complete.enabled=false",
				"withus.scheduler.campaign-schedule.enabled=false",
				"withus.scheduler.workflow-engine.enabled=false",
				"withus.scheduler.workflow-recovery.enabled=false")
			.run();

		// 이 캠페인 건이 몇 건 나가 선점 묶음이 발송 도중인 상태가 될 때까지 기다린다
		long deadline = System.currentTimeMillis() + 60_000;
		while (count("SENT") < SENT_BEFORE_CLOSE && System.currentTimeMillis() < deadline) {
			Thread.sleep(200);
		}
		assertThat(count("SENT")).as("종료 전에 발송이 진행 중이어야 한다").isGreaterThanOrEqualTo(SENT_BEFORE_CLOSE);
		assertThat(count("SENDING")).as("종료 직전 선점분이 남아 있어야 시나리오가 성립한다").isPositive();

		// ── 정상 종료 ──
		long closeStarted = System.nanoTime();
		runningApp.close();
		long closeMillis = (System.nanoTime() - closeStarted) / 1_000_000;

		// 종료 단계 제한(30초)에 걸리지 않고 곧바로 닫혀야 한다(현재 1건만 마침)
		assertThat(closeMillis).as("close() 소요(ms)").isLessThan(20_000);
		// 선점분이 SENDING 으로 남지 않았다(10분 뒤 UNKNOWN_RESULT 누락 없음)
		assertThat(count("SENDING")).as("종료 후 SENDING").isZero();
		long sentAtClose = count("SENT");
		assertThat(count("PENDING")).isEqualTo(TOTAL - sentAtClose);
		// 되돌린 건은 재시도 횟수를 소모하지 않았다
		assertThat(jdbcTemplate.queryForObject(
			"SELECT count(*) FROM send_log WHERE campaign_id = ? AND status = 'PENDING' AND attempt_count <> 0",
			Long.class, campaignId)).isZero();

		// ── 재기동 후: 남은 PENDING 이 이어서 모두 나가고, 이미 보낸 건은 다시 나가지 않는다 ──
		for (int guard = 0; guard < 10 && count("PENDING") > 0; guard++) {
			sendDispatcher.dispatch();
		}
		assertThat(statusCounts()).containsEntry("SENT", (long) TOTAL).doesNotContainKeys("PENDING", "SENDING", "FAILED");
		assertThat(jdbcTemplate.queryForObject(
			"SELECT count(DISTINCT provider_message_id) FROM send_log WHERE campaign_id = ?", Long.class, campaignId))
			.as("건마다 한 번씩만 발송됐다").isEqualTo((long) TOTAL);
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
}
