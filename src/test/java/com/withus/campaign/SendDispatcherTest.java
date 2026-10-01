package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
import com.withus.campaign.mapper.SendLogMapper;
import com.withus.campaign.mapper.TemplateMapper;
import com.withus.campaign.service.SendDispatcher;
import com.withus.campaign.service.messaging.MessageSenderRouter;
import com.withus.common.domain.Channel;
import com.withus.customer.service.ConsentService;

/**
 * 발송 디스패처 (발송 큐 Plan 3장) — 우선순위 선점, 속도 제한, 재시작 후 이어서 발송 검증
 * 로컬 Docker DB + Mailpit(SMTP) 이 떠 있어야 한다. @Transactional 을 쓰지 않는다: 디스패처는
 * 선점·결과 기록을 각각 자체 커밋되는 짧은 트랜잭션으로 수행하므로, 테스트도 실제 커밋을 봐야 검증된다.
 * 끝나고 직접 정리한다
 */
// 백그라운드 @Scheduled 가 테스트와 같은 DB 를 동시에 건드리면 타이밍 단언이 흔들리므로 끈다
@SpringBootTest(properties = "withus.scheduler.send-dispatcher.enabled=false")
class SendDispatcherTest {

	@Autowired
	SendDispatcher sendDispatcher;
	@Autowired
	SendLogMapper sendLogMapper;
	@Autowired
	TemplateMapper templateMapper;
	@Autowired
	MessageSenderRouter messageSenderRouter;
	@Autowired
	ConsentService consentService;
	@Autowired
	MemberMapper memberMapper;
	@Autowired
	JdbcTemplate jdbcTemplate;

	Long memberId;
	Long segmentId;
	Long campaignId;
	Long templateId;
	final List<Long> customerIds = new ArrayList<>();

	@BeforeEach
	void setUp() {
		Member member = new Member();
		member.setEmail("test-" + UUID.randomUUID() + "@withus.local");
		member.setPassword("x");
		member.setName("테스트");
		member.setRole(Role.MANAGER);
		memberMapper.insert(member);
		memberId = member.getMemberId();

		jdbcTemplate.update("INSERT INTO template (channel, name, subject, body, ad_yn, created_by) "
			+ "VALUES ('EMAIL', ?, '제목', '본문', 'N', ?)", "디스패처 테스트 템플릿", memberId);
		templateId = jdbcTemplate.queryForObject("SELECT max(template_id) FROM template", Long.class);

		jdbcTemplate.update("INSERT INTO segment (name, created_by) VALUES (?, ?)", "세그먼트", memberId);
		segmentId = jdbcTemplate.queryForObject("SELECT max(segment_id) FROM segment", Long.class);

		jdbcTemplate.update(
			"INSERT INTO campaign (name, type, status, segment_id, template_id, created_by) "
				+ "VALUES (?, 'ONE_TIME', 'ACTIVE', ?, ?, ?)",
			"캠페인", segmentId, templateId, memberId);
		campaignId = jdbcTemplate.queryForObject("SELECT max(campaign_id) FROM campaign", Long.class);
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

	private long newCustomer() {
		return newCustomer("Y");
	}

	private long newCustomer(String emailConsentYn) {
		jdbcTemplate.update(
			"INSERT INTO customer (email, joined_at, source, email_consent_yn) VALUES (?, now(), 'MANUAL', ?)",
			"customer-" + UUID.randomUUID() + "@withus.local", emailConsentYn);
		long customerId = jdbcTemplate.queryForObject("SELECT max(customer_id) FROM customer", Long.class);
		customerIds.add(customerId);
		return customerId;
	}

	private void enqueue(long customerId, short priority) {
		SendLog log = SendLog.builder()
			.campaignId(campaignId)
			.customerId(customerId)
			.recipient("customer-" + customerId + "@withus.local")
			.channel(Channel.EMAIL)
			.status(SendStatus.PENDING)
			.kind(SendKind.CAMPAIGN)
			.priority(priority)
			.build();
		sendLogMapper.insertOneTimeBatch(List.of(log));
	}

	@Test
	void priority_1이_3보다_먼저_나간다() {
		long lowPriorityCustomer = newCustomer();
		long highPriorityCustomer = newCustomer();
		enqueue(lowPriorityCustomer, SendLog.PRIORITY_CAMPAIGN_BULK); // 3
		enqueue(highPriorityCustomer, SendLog.PRIORITY_TEST); // 1

		sendDispatcher.dispatch();

		OffsetDateTime highSentAt = sentAtOf(highPriorityCustomer);
		OffsetDateTime lowSentAt = sentAtOf(lowPriorityCustomer);
		assertThat(highSentAt).as("priority 1 이 priority 3 보다 먼저 보내져야 한다").isBefore(lowSentAt);
	}

	@Test
	void rate가_1이면_5건이_수초에_걸쳐_나간다() {
		for (int i = 0; i < 5; i++) {
			enqueue(newCustomer(), SendLog.PRIORITY_CAMPAIGN_BULK);
		}

		long start = System.nanoTime();
		sendDispatcher.dispatch();
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;

		assertThat(elapsedMs).as("local 프로필 ses.max-send-rate=1 이므로 5건이면 최소 4초는 걸려야 한다")
			.isGreaterThanOrEqualTo(3_500);
		Long sentCount = jdbcTemplate.queryForObject(
			"SELECT count(*) FROM send_log WHERE campaign_id = ? AND status = 'SENT'", Long.class, campaignId);
		assertThat(sentCount).isEqualTo(5L);
	}

	@Test
	void dispatch를_다시_불러도_이미_보낸_건은_건드리지_않는다() {
		enqueue(newCustomer(), SendLog.PRIORITY_CAMPAIGN_BULK);
		sendDispatcher.dispatch(); // 전부 처리됨 (재시작 전 상태)

		// 재시작을 흉내낸다: 완전히 새 SendDispatcher 인스턴스로 다시 호출해도 더 처리할 PENDING 이 없어야 한다
		SendDispatcher restarted = new SendDispatcher(sendLogMapper, templateMapper, messageSenderRouter,
			consentService, 1, "08:00", "20:50", false);
		restarted.dispatch();

		Long pendingCount = jdbcTemplate.queryForObject(
			"SELECT count(*) FROM send_log WHERE campaign_id = ? AND status = 'PENDING'", Long.class, campaignId);
		assertThat(pendingCount).isZero();
	}

	@Test
	void 적재_후_수신거부한_고객은_발송_직전_재확인에서_SKIPPED가_된다() {
		long customerId = newCustomer("Y");
		enqueue(customerId, SendLog.PRIORITY_CAMPAIGN_BULK);
		jdbcTemplate.update("UPDATE customer SET email_consent_yn = 'N' WHERE customer_id = ?", customerId);

		sendDispatcher.dispatch();

		String status = jdbcTemplate.queryForObject(
			"SELECT status FROM send_log WHERE campaign_id = ? AND customer_id = ?", String.class, campaignId,
			customerId);
		assertThat(status).isEqualTo("SKIPPED");
	}

	@Test
	void 선점_이후_캠페인이_PAUSED되면_SKIPPED가_아니라_PENDING으로_되돌아간다() {
		long customerId = newCustomer();
		enqueue(customerId, SendLog.PRIORITY_CAMPAIGN_BULK);
		jdbcTemplate.update("UPDATE campaign SET status = 'PAUSED' WHERE campaign_id = ?", campaignId);

		// claimBatch 는 PAUSED 를 걸러내므로, 선점 이후 상태가 바뀐 상황을 흉내 내려면 재확인 로직을 직접 호출해야 한다
		SendLog claimed = SendLog.builder()
			.sendLogId(sendLogIdOf(customerId))
			.campaignId(campaignId)
			.customerId(customerId)
			.channel(Channel.EMAIL)
			.kind(SendKind.CAMPAIGN)
			.build();
		sendDispatcher.processOne(claimed);

		String status = jdbcTemplate.queryForObject(
			"SELECT status FROM send_log WHERE campaign_id = ? AND customer_id = ?", String.class, campaignId,
			customerId);
		assertThat(status).isEqualTo("PENDING");
	}

	private long sendLogIdOf(long customerId) {
		return jdbcTemplate.queryForObject(
			"SELECT send_log_id FROM send_log WHERE campaign_id = ? AND customer_id = ?", Long.class, campaignId,
			customerId);
	}

	@Test
	void TEST_발송은_시간창과_무관하게_즉시_나간다() {
		SendLog testLog = SendLog.builder()
			.campaignId(campaignId)
			.recipient("tester@withus.local")
			.channel(Channel.EMAIL)
			.status(SendStatus.PENDING)
			.kind(SendKind.TEST)
			.priority(SendLog.PRIORITY_TEST)
			.build();
		sendLogMapper.insertOneTimeBatch(List.of(testLog));

		sendDispatcher.dispatch();

		String status = jdbcTemplate.queryForObject(
			"SELECT status FROM send_log WHERE campaign_id = ? AND kind = 'TEST'", String.class, campaignId);
		assertThat(status).isEqualTo("SENT");
	}

	private OffsetDateTime sentAtOf(long customerId) {
		return jdbcTemplate.queryForObject(
			"SELECT sent_at FROM send_log WHERE campaign_id = ? AND customer_id = ?",
			OffsetDateTime.class, campaignId, customerId);
	}
}
