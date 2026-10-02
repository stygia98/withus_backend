package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.withus.auth.domain.Member;
import com.withus.auth.domain.Role;
import com.withus.auth.mapper.MemberMapper;
import com.withus.campaign.service.CampaignCompleteJob;
import com.withus.campaign.service.CampaignScheduleJob;

/**
 * 예약 실행 스케줄러와 자동 완료(캠페인 4/4). 로컬 Docker DB, 테스트마다 롤백.
 * 발송 큐 스케줄러는 끈다(withus.scheduler.send-dispatcher.enabled=false) — 여기서는
 * 적재까지만 확인하고 실제 발송은 SendDispatcherTest 영역이다
 */
@SpringBootTest(properties = "withus.scheduler.send-dispatcher.enabled=false")
@Transactional
class CampaignScheduleJobTest {

	@Autowired
	CampaignScheduleJob campaignScheduleJob;
	@Autowired
	CampaignCompleteJob campaignCompleteJob;
	@Autowired
	MemberMapper memberMapper;
	@Autowired
	JdbcTemplate jdbc;

	long memberId;
	long segmentId;
	long templateId;

	@BeforeEach
	void setUp() {
		Member member = new Member();
		member.setEmail("campaign-job-" + UUID.randomUUID() + "@withus.local");
		member.setPassword("x");
		member.setName("테스트");
		member.setRole(Role.MANAGER);
		memberMapper.insert(member);
		memberId = member.getMemberId();

		segmentId = jdbc.queryForObject(
			"INSERT INTO segment (name, created_by) VALUES ('세그먼트', ?) RETURNING segment_id", Long.class, memberId);
		// segment_rule 없는 세그먼트는 findTargetCustomers 가 실패하므로(SegmentServiceImpl), 전체 포함 규칙을 심는다
		jdbc.update("""
			INSERT INTO segment_rule (segment_id, rule_json) VALUES (?,
			  '{"operator":"AND","groups":[{"operator":"AND","conditions":[{"field":"dormant","op":"EQ","value":"N"}]}]}'::jsonb)
			""", segmentId);
		templateId = jdbc.queryForObject("""
			INSERT INTO template (channel, name, subject, body, ad_yn, created_by)
			VALUES ('EMAIL', '템플릿', '제목', '<p>본문</p>', 'N', ?) RETURNING template_id
			""", Long.class, memberId);
	}

	private long customer() {
		return jdbc.queryForObject("""
			INSERT INTO customer (email, joined_at, source, email_consent_yn) VALUES (?, now(), 'MANUAL', 'Y')
			RETURNING customer_id
			""", Long.class, "job-" + UUID.randomUUID() + "@withus.local");
	}

	private long scheduledCampaign(OffsetDateTime scheduledAt) {
		return jdbc.queryForObject("""
			INSERT INTO campaign (name, type, status, segment_id, template_id, scheduled_at, created_by)
			VALUES ('캠페인', 'ONE_TIME', 'SCHEDULED', ?, ?, ?, ?) RETURNING campaign_id
			""", Long.class, segmentId, templateId, scheduledAt, memberId);
	}

	@Test
	void 예약_시각이_지나면_활성화하고_대상을_적재한다() {
		long customerId = customer();
		long campaignId = scheduledCampaign(OffsetDateTime.now().minusMinutes(1));

		int started = campaignScheduleJob.activateScheduled();

		assertThat(started).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT status FROM campaign WHERE campaign_id = ?", String.class, campaignId))
			.isEqualTo("ACTIVE");
		assertThat(jdbc.queryForObject(
			"SELECT count(*) FROM send_log WHERE campaign_id = ? AND customer_id = ?", Long.class, campaignId,
			customerId)).isEqualTo(1);
	}

	@Test
	void 예약_시각이_안_지나면_건드리지_않는다() {
		long campaignId = scheduledCampaign(OffsetDateTime.now().plusHours(1));

		int started = campaignScheduleJob.activateScheduled();

		assertThat(started).isZero();
		assertThat(jdbc.queryForObject("SELECT status FROM campaign WHERE campaign_id = ?", String.class, campaignId))
			.isEqualTo("SCHEDULED");
	}

	@Test
	void PENDING_SENDING이_없으면_자동으로_완료된다() {
		customer();
		long campaignId = scheduledCampaign(OffsetDateTime.now().minusMinutes(1));
		campaignScheduleJob.activateScheduled();
		jdbc.update("UPDATE send_log SET status = 'SENT', sent_at = now() WHERE campaign_id = ?", campaignId);

		int completed = campaignCompleteJob.completeFinished();

		assertThat(completed).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT status FROM campaign WHERE campaign_id = ?", String.class, campaignId))
			.isEqualTo("COMPLETED");
		assertThat(jdbc.queryForObject("SELECT ended_at IS NOT NULL FROM campaign WHERE campaign_id = ?",
			Boolean.class, campaignId)).isTrue();
	}

	@Test
	void PENDING이_남아있으면_완료되지_않는다() {
		customer();
		long campaignId = scheduledCampaign(OffsetDateTime.now().minusMinutes(1));
		campaignScheduleJob.activateScheduled();
		// send_log 는 PENDING 그대로 둔다

		int completed = campaignCompleteJob.completeFinished();

		assertThat(completed).isZero();
		assertThat(jdbc.queryForObject("SELECT status FROM campaign WHERE campaign_id = ?", String.class, campaignId))
			.isEqualTo("ACTIVE");
	}

	@Test
	void 대상이_없는_캠페인도_시작_즉시_완료_대상이_된다() {
		// 아무도 맞지 않는 규칙(나이는 따옴표 없는 JSON 숫자 0~150 만 허용되므로 150 초과)으로 바꾼다 — 시드 고객이 있는 DB 에서도 대상 0명이어야 PENDING 이 안 생긴다
		jdbc.update("UPDATE segment_rule SET rule_json = '{\"operator\":\"AND\",\"groups\":[{\"operator\":\"AND\","
			+ "\"conditions\":[{\"field\":\"age\",\"op\":\"GT\",\"value\":150}]}]}'::jsonb WHERE segment_id = ?", segmentId);
		long campaignId = scheduledCampaign(OffsetDateTime.now().minusMinutes(1));
		campaignScheduleJob.activateScheduled();

		int completed = campaignCompleteJob.completeFinished();

		assertThat(completed).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT status FROM campaign WHERE campaign_id = ?", String.class, campaignId))
			.isEqualTo("COMPLETED");
	}
}
