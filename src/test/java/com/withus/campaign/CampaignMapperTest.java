package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
import com.withus.campaign.domain.Campaign;
import com.withus.campaign.domain.CampaignStatus;
import com.withus.campaign.domain.CampaignType;
import com.withus.campaign.mapper.CampaignMapper;

/**
 * 캠페인 Mapper(구조 1/3 전 단계) — 목록·상세·insert·update·상태 변경(낙관적 검사) 검증
 * 로컬 Docker DB 를 쓰고 테스트마다 롤백한다
 */
@SpringBootTest
@Transactional
class CampaignMapperTest {

	@Autowired
	CampaignMapper campaignMapper;
	@Autowired
	MemberMapper memberMapper;
	@Autowired
	JdbcTemplate jdbcTemplate;

	long memberId;
	long segmentId;

	@BeforeEach
	void setUp() {
		Member member = new Member();
		member.setEmail("test-" + UUID.randomUUID() + "@withus.local");
		member.setPassword("x");
		member.setName("테스트");
		member.setRole(Role.MANAGER);
		memberMapper.insert(member);
		memberId = member.getMemberId();

		jdbcTemplate.update("INSERT INTO segment (name, created_by) VALUES (?, ?)", "세그먼트", memberId);
		segmentId = jdbcTemplate.queryForObject("SELECT max(segment_id) FROM segment", Long.class);
	}

	private Campaign newCampaign(CampaignType type, CampaignStatus status) {
		Campaign campaign = new Campaign();
		campaign.setName("캠페인");
		campaign.setType(type);
		campaign.setStatus(status);
		campaign.setSegmentId(segmentId);
		campaign.setCreatedBy(memberId);
		return campaign;
	}

	@Test
	void 생성_목록_상세_수정이_정상_동작한다() {
		long oneTimeCountBefore = campaignMapper.count(CampaignType.ONE_TIME, null);

		Campaign campaign = newCampaign(CampaignType.ONE_TIME, CampaignStatus.DRAFT);
		campaignMapper.insert(campaign);
		assertThat(campaign.getCampaignId()).isNotNull();

		List<Campaign> list = campaignMapper.findList(CampaignType.ONE_TIME, null, 0, 20);
		assertThat(list).extracting(Campaign::getCampaignId).contains(campaign.getCampaignId());
		assertThat(campaignMapper.count(CampaignType.ONE_TIME, null)).isEqualTo(oneTimeCountBefore + 1);

		Campaign found = campaignMapper.findById(campaign.getCampaignId());
		assertThat(found.getName()).isEqualTo("캠페인");
		assertThat(found.getStatus()).isEqualTo(CampaignStatus.DRAFT);

		found.setName("캠페인(수정)");
		campaignMapper.update(found);
		assertThat(campaignMapper.findById(campaign.getCampaignId()).getName()).isEqualTo("캠페인(수정)");
	}

	@Test
	void status_필터로_좁혀진다() {
		Campaign draft = newCampaign(CampaignType.ONE_TIME, CampaignStatus.DRAFT);
		campaignMapper.insert(draft);
		Campaign active = newCampaign(CampaignType.ONE_TIME, CampaignStatus.ACTIVE);
		campaignMapper.insert(active);

		List<Campaign> activeOnly = campaignMapper.findList(null, CampaignStatus.ACTIVE, 0, 20);
		assertThat(activeOnly).extracting(Campaign::getCampaignId)
			.contains(active.getCampaignId())
			.doesNotContain(draft.getCampaignId());
	}

	@Test
	void 상태_변경은_현재_상태가_같을_때만_성공한다() {
		Campaign campaign = newCampaign(CampaignType.ONE_TIME, CampaignStatus.DRAFT);
		campaignMapper.insert(campaign);

		int updated = campaignMapper.updateStatus(campaign.getCampaignId(), CampaignStatus.DRAFT,
			CampaignStatus.ACTIVE);
		assertThat(updated).isEqualTo(1);
		assertThat(campaignMapper.findById(campaign.getCampaignId()).getStatus()).isEqualTo(CampaignStatus.ACTIVE);

		// 이미 ACTIVE 인데 DRAFT 를 기대값으로 다시 시도하면 0건 — 낙관적 검사 실패
		int conflicting = campaignMapper.updateStatus(campaign.getCampaignId(), CampaignStatus.DRAFT,
			CampaignStatus.PAUSED);
		assertThat(conflicting).isEqualTo(0);
		assertThat(campaignMapper.findById(campaign.getCampaignId()).getStatus()).isEqualTo(CampaignStatus.ACTIVE);
	}

	@Test
	void 시작_선점은_읽은_시각이_같을_때_한_번만_성공한다() {
		Campaign campaign = newCampaign(CampaignType.ONE_TIME, CampaignStatus.DRAFT);
		campaignMapper.insert(campaign);
		var readAt = campaignMapper.findById(campaign.getCampaignId()).getUpdatedAt();

		var claimed = campaignMapper.claimStart(campaign.getCampaignId(), readAt);
		var second = campaignMapper.claimStart(campaign.getCampaignId(), readAt); // 동시에 시작한 다른 요청

		assertThat(claimed).isNotNull().isNotEqualTo(readAt);
		assertThat(second).as("같은 시각을 읽고 온 두 번째 요청은 선점하지 못한다").isNull();
	}

	@Test
	void 선점_뒤에_수정이나_취소가_끼면_ACTIVE로_바뀌지_않는다() {
		Campaign campaign = newCampaign(CampaignType.ONE_TIME, CampaignStatus.SCHEDULED);
		campaignMapper.insert(campaign);
		var readAt = campaignMapper.findById(campaign.getCampaignId()).getUpdatedAt();
		var claimedAt = campaignMapper.claimStart(campaign.getCampaignId(), readAt);

		// 적재 중에 사용자가 예약을 취소했다(updated_at 이 바뀐다)
		jdbcTemplate.update("UPDATE campaign SET status = 'DRAFT', updated_at = clock_timestamp() + interval '1 second' WHERE campaign_id = ?", campaign.getCampaignId());

		assertThat(campaignMapper.start(campaign.getCampaignId(), claimedAt)).isZero();
		assertThat(campaignMapper.findById(campaign.getCampaignId()).getStatus()).isEqualTo(CampaignStatus.DRAFT);
	}

	@Test
	void 선점_뒤에_아무도_끼지_않으면_ACTIVE가_된다() {
		Campaign campaign = newCampaign(CampaignType.ONE_TIME, CampaignStatus.DRAFT);
		campaignMapper.insert(campaign);
		var readAt = campaignMapper.findById(campaign.getCampaignId()).getUpdatedAt();
		var claimedAt = campaignMapper.claimStart(campaign.getCampaignId(), readAt);

		assertThat(campaignMapper.start(campaign.getCampaignId(), claimedAt)).isEqualTo(1);
		assertThat(campaignMapper.findById(campaign.getCampaignId()).getStatus()).isEqualTo(CampaignStatus.ACTIVE);
	}
}
