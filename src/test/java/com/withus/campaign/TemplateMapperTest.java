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
import com.withus.campaign.domain.Template;
import com.withus.campaign.mapper.TemplateMapper;
import com.withus.common.domain.Channel;

/**
 * 템플릿 Mapper (PRD 10.3 관련 없음, API_SPEC 5장 전 단계 — CRUD·사용 여부 조회 검증)
 * 로컬 Docker DB 를 쓰고 테스트마다 롤백한다
 */
@SpringBootTest
@Transactional
class TemplateMapperTest {

	@Autowired
	TemplateMapper templateMapper;
	@Autowired
	MemberMapper memberMapper;
	@Autowired
	JdbcTemplate jdbcTemplate;

	long memberId;

	@BeforeEach
	void setUp() {
		Member member = new Member();
		member.setEmail("test-" + UUID.randomUUID() + "@withus.local");
		member.setPassword("x");
		member.setName("테스트");
		member.setRole(Role.MANAGER);
		memberMapper.insert(member);
		memberId = member.getMemberId();
	}

	private Template newTemplate(Channel channel, String name) {
		Template template = new Template();
		template.setChannel(channel);
		template.setName(name);
		template.setSubject(channel == Channel.EMAIL ? "제목" : null);
		template.setBody("본문 {{name}}");
		template.setAdYn("Y");
		template.setCreatedBy(memberId);
		return template;
	}

	private long newSegment() {
		jdbcTemplate.update("INSERT INTO segment (name, created_by) VALUES (?, ?)", "세그먼트", memberId);
		return jdbcTemplate.queryForObject("SELECT max(segment_id) FROM segment", Long.class);
	}

	private long newCampaign(long segmentId, Long templateId, String status) {
		jdbcTemplate.update(
			"INSERT INTO campaign (name, type, status, segment_id, template_id, created_by) VALUES (?, 'ONE_TIME', ?, ?, ?, ?)",
			"캠페인", status, segmentId, templateId, memberId);
		return jdbcTemplate.queryForObject("SELECT max(campaign_id) FROM campaign", Long.class);
	}

	@Test
	void CRUD_흐름_생성_목록_상세_수정_삭제() {
		Template template = newTemplate(Channel.EMAIL, "환영 메일");
		templateMapper.insert(template);
		assertThat(template.getTemplateId()).isNotNull();

		List<Template> list = templateMapper.findList("EMAIL", 0, 20);
		assertThat(list).extracting(Template::getTemplateId).contains(template.getTemplateId());
		assertThat(templateMapper.count("EMAIL")).isGreaterThanOrEqualTo(1);
		assertThat(templateMapper.count("SMS")).isEqualTo(0);

		Template found = templateMapper.findById(template.getTemplateId());
		assertThat(found.getName()).isEqualTo("환영 메일");
		assertThat(found.getChannel()).isEqualTo(Channel.EMAIL);

		found.setName("환영 메일(수정)");
		templateMapper.update(found);
		assertThat(templateMapper.findById(template.getTemplateId()).getName()).isEqualTo("환영 메일(수정)");

		templateMapper.delete(template.getTemplateId());
		assertThat(templateMapper.findById(template.getTemplateId())).isNull();
	}

	@Test
	void 일회성_캠페인이_직접_참조하면_상태에_따라_사용_여부가_갈린다() {
		Template template = newTemplate(Channel.EMAIL, "캠페인용");
		templateMapper.insert(template);
		long segmentId = newSegment();
		long campaignId = newCampaign(segmentId, template.getTemplateId(), "ACTIVE");

		assertThat(templateMapper.existsInUseByStatus(template.getTemplateId())).isTrue();
		assertThat(templateMapper.existsReferenced(template.getTemplateId())).isTrue();

		jdbcTemplate.update("UPDATE campaign SET status = 'COMPLETED' WHERE campaign_id = ?", campaignId);

		assertThat(templateMapper.existsInUseByStatus(template.getTemplateId())).isFalse();
		assertThat(templateMapper.existsReferenced(template.getTemplateId())).isTrue(); // 완료돼도 참조는 남아 삭제는 막힌다
	}

	@Test
	void 워크플로우_SEND_노드가_참조하면_캠페인_상태로_사용_여부를_판정한다() {
		Template template = newTemplate(Channel.EMAIL, "워크플로우용");
		templateMapper.insert(template);
		long segmentId = newSegment();
		long campaignId = newCampaign(segmentId, null, "DRAFT");
		jdbcTemplate.update(
			"INSERT INTO workflow_step (campaign_id, node_type, config_json) VALUES (?, 'SEND_EMAIL', ?::jsonb)",
			campaignId, "{\"templateId\": " + template.getTemplateId() + "}");

		assertThat(templateMapper.existsReferenced(template.getTemplateId())).isTrue();
		assertThat(templateMapper.existsInUseByStatus(template.getTemplateId())).isFalse(); // DRAFT 는 사용 중이 아님

		jdbcTemplate.update("UPDATE campaign SET status = 'ACTIVE' WHERE campaign_id = ?", campaignId);
		assertThat(templateMapper.existsInUseByStatus(template.getTemplateId())).isTrue();
	}

	@Test
	void 참조가_없으면_사용_여부_쿼리가_모두_false() {
		Template template = newTemplate(Channel.SMS, "미사용");
		templateMapper.insert(template);

		assertThat(templateMapper.existsInUseByStatus(template.getTemplateId())).isFalse();
		assertThat(templateMapper.existsReferenced(template.getTemplateId())).isFalse();
	}
}
