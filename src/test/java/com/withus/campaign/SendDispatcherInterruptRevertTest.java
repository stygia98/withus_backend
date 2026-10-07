package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
import com.withus.campaign.service.SendDispatcher;
import com.withus.campaign.service.SendQueueService;
import com.withus.common.domain.Channel;

/**
 * 정상 종료(인터럽트) 경로를 실제 DB·커넥션 풀로 확인한다 (이슈 #90 항목 1). 단위 테스트(SendDispatcherInterruptRevertUnitTest)는
 * 되돌리기 호출이 일어나는지만 보지만, 여기서는 TokenBucket 대기 중 인터럽트로 스레드의 인터럽트 플래그가 선 채로
 * revertUnprocessed 의 DB 호출(커넥션 획득·UPDATE)이 실제로 성공해 선점분이 PENDING 으로 돌아오는지를 본다 —
 * 되돌리기가 실패하면 건이 SENDING 으로 남아 10분 뒤 UNKNOWN_RESULT 로 누락된다.
 * 다른 캠페인의 잔여 PENDING 이 같은 선점 묶음에 섞여도 단언은 이 테스트가 만든 건만 본다.
 * 로컬 Docker DB + Mailpit 이 떠 있어야 한다. 디스패처는 자체 커밋 트랜잭션을 쓰므로 @Transactional 없이 직접 정리한다
 */
@SpringBootTest(properties = {
	"withus.scheduler.send-dispatcher.enabled=false",
	"ses.max-send-rate=1" // 초당 1건 — 첫 건은 바로 나가고 둘째 건부터 TokenBucket 에서 약 1초 대기한다(기본값과 같다)
})
class SendDispatcherInterruptRevertTest {

	private static final int COUNT = 5;

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
	ExecutorService executor = Executors.newSingleThreadExecutor();

	@BeforeEach
	void setUp() {
		Member member = new Member();
		member.setEmail("interrupt-" + UUID.randomUUID() + "@withus.local");
		member.setPassword("x");
		member.setName("인터럽트 테스트");
		member.setRole(Role.MANAGER);
		memberMapper.insert(member);
		memberId = member.getMemberId();

		// 비광고 템플릿 — 시간창을 적용받지 않아 실행 시각과 무관하게 나간다
		jdbcTemplate.update("INSERT INTO template (channel, name, subject, body, ad_yn, created_by) "
			+ "VALUES ('EMAIL', ?, '제목', '본문', 'N', ?)", "인터럽트 템플릿", memberId);
		templateId = jdbcTemplate.queryForObject("SELECT max(template_id) FROM template", Long.class);
		jdbcTemplate.update("INSERT INTO segment (name, created_by) VALUES (?, ?)", "인터럽트 세그먼트", memberId);
		segmentId = jdbcTemplate.queryForObject("SELECT max(segment_id) FROM segment", Long.class);
		jdbcTemplate.update("INSERT INTO campaign (name, type, status, segment_id, template_id, created_by) "
			+ "VALUES (?, 'ONE_TIME', 'ACTIVE', ?, ?, ?)", "인터럽트 캠페인", segmentId, templateId, memberId);
		campaignId = jdbcTemplate.queryForObject("SELECT max(campaign_id) FROM campaign", Long.class);

		String tag = UUID.randomUUID().toString();
		jdbcTemplate.update("INSERT INTO customer (email, joined_at, source, email_consent_yn) "
			+ "SELECT 'interrupt-' || ? || '-' || i || '@withus.local', now(), 'MANUAL', 'Y' FROM generate_series(1, ?) AS i",
			tag, COUNT);
		customerIds = jdbcTemplate.queryForList("SELECT customer_id FROM customer WHERE email LIKE ? ORDER BY customer_id",
			Long.class, "interrupt-" + tag + "-%@withus.local");
	}

	@AfterEach
	void cleanUp() {
		executor.shutdownNow();
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
	void TokenBucket_대기_중_인터럽트를_받아도_실제_DB에서_선점분이_PENDING으로_돌아오고_시도를_소모하지_않는다() throws Exception {
		assertThat(sendQueueService.enqueueOneTime(campaignId, customerIds, Channel.EMAIL, SendKind.CAMPAIGN))
			.isEqualTo(COUNT);

		AtomicReference<Thread> dispatchThread = new AtomicReference<>();
		Future<?> done = executor.submit(() -> {
			dispatchThread.set(Thread.currentThread());
			sendDispatcher.dispatch();
		});
		// 선점·첫 건 발송을 마치고 둘째 건이 TokenBucket 의 sleep 에서 대기(TIMED_WAITING)할 때까지 기다린다 — sleep 시간에 의존하지 않는다
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
		while (dispatchThread.get() == null || dispatchThread.get().getState() != Thread.State.TIMED_WAITING) {
			assertThat(System.nanoTime()).as("TokenBucket 대기에 들어가야 한다").isLessThan(deadline);
			Thread.sleep(5);
		}
		dispatchThread.get().interrupt();
		done.get(10, TimeUnit.SECONDS); // 인터럽트를 받고 dispatch() 가 끝나야 한다

		// 이 테스트가 만든 건은 SENDING 으로 남지 않는다 — 남으면 10분 뒤 UNKNOWN_RESULT 로 누락된다
		assertThat(countOf("SENDING")).as("선점만 되고 처리 못 한 건이 SENDING 으로 남으면 안 된다").isZero();
		assertThat(countOf("FAILED")).isZero();
		// 되돌린 건은 시도로 세지 않았고 보낸 적도 없다. 나간 건(0 또는 1건: 다른 캠페인 건이 먼저 선점됐을 수 있다)과 합쳐 전부 설명된다
		long pending = countOf("PENDING");
		long sent = countOf("SENT");
		assertThat(pending + sent).isEqualTo(COUNT);
		assertThat(sent).isLessThanOrEqualTo(1);
		assertThat(jdbcTemplate.queryForObject(
			"SELECT count(*) FROM send_log WHERE campaign_id = ? AND status = 'PENDING' "
				+ "AND (attempt_count <> 0 OR provider_message_id IS NOT NULL)", Long.class, campaignId))
			.as("PENDING 으로 돌아온 건은 attempt_count 0, 발송 식별자 없음").isZero();
	}

	private long countOf(String status) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM send_log WHERE campaign_id = ? AND status = ?",
			Long.class, campaignId, status);
	}
}
