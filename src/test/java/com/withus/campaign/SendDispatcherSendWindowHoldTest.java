package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
import com.withus.campaign.service.SendDispatcher;
import com.withus.campaign.service.SendQueueService;
import com.withus.common.domain.Channel;

/**
 * 발송 시간창(08:00~20:50) 밖에 도달한 광고 건이 실제로 보류되는지 확인한다 (PRD 10.3 "야간에 도달한 워크플로우 광고
 * 발송은 다음 날 08시에 나간다"). SendWindowTest 는 판정 로직만 검증하므로, 여기서는 SendDispatcher.recheck 가
 * holdForSendWindow 를 불러 PENDING 으로 되돌리고 다음 선점에서 빠지는 연결(배선)을 확인한다.
 * 창을 08:00:00~08:00:00 으로 좁혀 두면 실행 시각이 정확히 08:00:00 이 아닌 한 항상 창 밖이라, 언제 돌려도
 * 보류되고 보류 시각은 항상 서울 08:00 이 된다. 로컬 Docker DB + Mailpit 이 떠 있어야 한다
 */
@SpringBootTest(properties = {
	"withus.scheduler.send-dispatcher.enabled=false",
	"withus.send-window.start=08:00:00",
	"withus.send-window.end=08:00:00"
})
class SendDispatcherSendWindowHoldTest {

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
	Long customerId;

	@BeforeEach
	void setUp() {
		Member member = new Member();
		member.setEmail("hold-" + UUID.randomUUID() + "@withus.local");
		member.setPassword("x");
		member.setName("시간창 테스트");
		member.setRole(Role.MANAGER);
		memberMapper.insert(member);
		memberId = member.getMemberId();

		// 광고성 템플릿 — 비광고는 시간창을 적용받지 않는다
		jdbcTemplate.update("INSERT INTO template (channel, name, subject, body, ad_yn, created_by) "
			+ "VALUES ('EMAIL', ?, '제목', '본문', 'Y', ?)", "시간창 보류 템플릿", memberId);
		templateId = jdbcTemplate.queryForObject("SELECT max(template_id) FROM template", Long.class);
		jdbcTemplate.update("INSERT INTO segment (name, created_by) VALUES (?, ?)", "시간창 세그먼트", memberId);
		segmentId = jdbcTemplate.queryForObject("SELECT max(segment_id) FROM segment", Long.class);
		jdbcTemplate.update("INSERT INTO campaign (name, type, status, segment_id, template_id, created_by) "
			+ "VALUES (?, 'ONE_TIME', 'ACTIVE', ?, ?, ?)", "시간창 캠페인", segmentId, templateId, memberId);
		campaignId = jdbcTemplate.queryForObject("SELECT max(campaign_id) FROM campaign", Long.class);

		jdbcTemplate.update("INSERT INTO customer (email, joined_at, source, email_consent_yn) "
			+ "VALUES (?, now(), 'MANUAL', 'Y')", "hold-" + UUID.randomUUID() + "@withus.local");
		customerId = jdbcTemplate.queryForObject("SELECT max(customer_id) FROM customer", Long.class);
	}

	@AfterEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM send_log WHERE campaign_id = ?", campaignId);
		jdbcTemplate.update("DELETE FROM campaign WHERE campaign_id = ?", campaignId);
		jdbcTemplate.update("DELETE FROM segment WHERE segment_id = ?", segmentId);
		jdbcTemplate.update("DELETE FROM template WHERE template_id = ?", templateId);
		jdbcTemplate.update("DELETE FROM customer WHERE customer_id = ?", customerId);
		jdbcTemplate.update("DELETE FROM member WHERE member_id = ?", memberId);
	}

	@Test
	void 시간창_밖의_광고_건은_발송하지_않고_다음_08시로_보류되며_다음_선점에서도_빠진다() {
		sendQueueService.enqueueOneTime(campaignId, List.of(customerId), Channel.EMAIL, SendKind.CAMPAIGN);

		sendDispatcher.dispatch();

		// 보내지 않았고, 시도로 세지도 않았으며(보류는 attempt_count 를 올리지 않는다), 다음 시도 시각만 08:00 으로 잡혔다
		assertThat(status()).isEqualTo("PENDING");
		assertThat(attemptCount()).isZero();
		assertThat(providerMessageId()).isNull();
		OffsetDateTime next = nextAttemptAt();
		assertThat(next).isAfter(OffsetDateTime.now());
		assertThat(next).isBefore(OffsetDateTime.now().plus(Duration.ofHours(25)));
		var seoul = next.atZoneSameInstant(ZoneId.of("Asia/Seoul"));
		assertThat(seoul.getHour()).isEqualTo(8);
		assertThat(seoul.getMinute()).isZero();

		// 보류 시각이 지나기 전에는 다시 선점되지 않는다 — 몇 번을 돌려도 그대로 PENDING 이고 보류 시각도 그대로다
		sendDispatcher.dispatch();
		sendDispatcher.dispatch();
		assertThat(status()).isEqualTo("PENDING");
		assertThat(attemptCount()).isZero();
		assertThat(providerMessageId()).isNull();
		assertThat(nextAttemptAt()).isEqualTo(next);
	}

	// 한 건만 적재했으므로 campaign_id 로 그 건을 찾는다
	private String status() {
		return jdbcTemplate.queryForObject("SELECT status FROM send_log WHERE campaign_id = ?", String.class, campaignId);
	}

	private int attemptCount() {
		return jdbcTemplate.queryForObject("SELECT attempt_count FROM send_log WHERE campaign_id = ?", Integer.class,
			campaignId);
	}

	private String providerMessageId() {
		return jdbcTemplate.queryForObject("SELECT provider_message_id FROM send_log WHERE campaign_id = ?",
			String.class, campaignId);
	}

	private OffsetDateTime nextAttemptAt() {
		return jdbcTemplate.queryForObject("SELECT next_attempt_at FROM send_log WHERE campaign_id = ?",
			OffsetDateTime.class, campaignId);
	}
}
