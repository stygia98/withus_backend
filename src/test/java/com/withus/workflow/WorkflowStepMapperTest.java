package com.withus.workflow;

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
import com.withus.campaign.domain.TriggerType;
import com.withus.campaign.mapper.CampaignMapper;
import com.withus.workflow.domain.NodeType;
import com.withus.workflow.domain.WorkflowStep;
import com.withus.workflow.mapper.WorkflowStepMapper;

import tools.jackson.databind.ObjectMapper;

/**
 * 워크플로우 구조 1/3: config_json 왕복, 일괄 insert 의 생성된 stepId, next·yes·no 연결 검증
 * 로컬 Docker DB 를 쓰고 테스트마다 롤백한다
 */
@SpringBootTest
@Transactional
class WorkflowStepMapperTest {

	@Autowired
	WorkflowStepMapper workflowStepMapper;
	@Autowired
	CampaignMapper campaignMapper;
	@Autowired
	MemberMapper memberMapper;
	@Autowired
	JdbcTemplate jdbcTemplate;
	@Autowired
	ObjectMapper objectMapper;

	long memberId;
	long campaignId;

	@BeforeEach
	void setUp() {
		Member member = new Member();
		member.setEmail("test-" + UUID.randomUUID() + "@withus.local");
		member.setPassword("x");
		member.setName("테스트");
		member.setRole(Role.MANAGER);
		memberMapper.insert(member);
		memberId = member.getMemberId();

		// campaign.segment_id 는 NOT NULL 이라 세그먼트를 먼저 만든다
		jdbcTemplate.update("INSERT INTO segment (name, created_by) VALUES (?, ?)", "세그먼트", memberId);
		long segmentId = jdbcTemplate.queryForObject("SELECT max(segment_id) FROM segment", Long.class);

		Campaign campaign = new Campaign();
		campaign.setName("워크플로우 캠페인");
		campaign.setType(CampaignType.WORKFLOW);
		campaign.setStatus(CampaignStatus.DRAFT);
		campaign.setSegmentId(segmentId);
		campaign.setTriggerType(TriggerType.CUSTOMER_REGISTERED);
		campaign.setCreatedBy(memberId);
		campaignMapper.insert(campaign);
		campaignId = campaign.getCampaignId();
	}

	private WorkflowStep step(NodeType nodeType, String configJson) {
		WorkflowStep step = new WorkflowStep();
		step.setCampaignId(campaignId);
		step.setNodeType(nodeType);
		step.setConfigJson(configJson);
		step.setDepth((short) 0);
		return step;
	}

	@Test
	void config_json이_왕복되고_생성된_stepId가_채워진다() {
		WorkflowStep trigger = step(NodeType.TRIGGER, "{\"triggerType\":\"CUSTOMER_REGISTERED\"}");
		WorkflowStep end = step(NodeType.END, "{}");

		int inserted = workflowStepMapper.insertBatch(List.of(trigger, end));

		assertThat(inserted).isEqualTo(2);
		assertThat(trigger.getStepId()).isNotNull();
		assertThat(end.getStepId()).isNotNull().isNotEqualTo(trigger.getStepId());

		List<WorkflowStep> found = workflowStepMapper.findByCampaignId(campaignId);
		assertThat(found).hasSize(2);
		WorkflowStep foundTrigger = found.stream().filter(s -> s.getNodeType() == NodeType.TRIGGER).findFirst()
			.orElseThrow();
		assertThat(objectMapper.readTree(foundTrigger.getConfigJson()))
			.isEqualTo(objectMapper.readTree("{\"triggerType\":\"CUSTOMER_REGISTERED\"}"));
	}

	@Test
	void updateLinks로_next_yes_no_연결이_저장된다() {
		WorkflowStep condition = step(NodeType.CONDITION, "{\"condition\":\"EMAIL_CLICKED\"}");
		WorkflowStep sendYes = step(NodeType.SEND_EMAIL, "{\"templateId\":1}");
		WorkflowStep sendNo = step(NodeType.SEND_EMAIL, "{\"templateId\":2}");
		workflowStepMapper.insertBatch(List.of(condition, sendYes, sendNo));

		workflowStepMapper.updateLinks(condition.getStepId(), null, sendYes.getStepId(), sendNo.getStepId());

		WorkflowStep found = workflowStepMapper.findByCampaignId(campaignId).stream()
			.filter(s -> s.getStepId().equals(condition.getStepId())).findFirst().orElseThrow();
		assertThat(found.getNextStepId()).isNull();
		assertThat(found.getYesStepId()).isEqualTo(sendYes.getStepId());
		assertThat(found.getNoStepId()).isEqualTo(sendNo.getStepId());
	}

	@Test
	void deleteByCampaignId로_구조를_통째로_지울_수_있다() {
		workflowStepMapper.insertBatch(List.of(step(NodeType.TRIGGER, "{}"), step(NodeType.END, "{}")));

		workflowStepMapper.deleteByCampaignId(campaignId);

		assertThat(workflowStepMapper.findByCampaignId(campaignId)).isEmpty();
	}

	@Test
	void updateLinksBatch로_여러_노드의_연결을_한_번에_저장한다() {
		WorkflowStep trigger = step(NodeType.TRIGGER, "{}");
		WorkflowStep condition = step(NodeType.CONDITION, "{}");
		WorkflowStep end = step(NodeType.END, "{}");
		workflowStepMapper.insertBatch(List.of(trigger, condition, end));
		trigger.setNextStepId(condition.getStepId());
		condition.setYesStepId(end.getStepId());
		condition.setNoStepId(end.getStepId());

		workflowStepMapper.updateLinksBatch(List.of(trigger, condition, end));

		List<WorkflowStep> found = workflowStepMapper.findByCampaignId(campaignId);
		assertThat(found).filteredOn(s -> s.getNodeType() == NodeType.TRIGGER)
			.allSatisfy(s -> assertThat(s.getNextStepId()).isEqualTo(condition.getStepId()));
		assertThat(found).filteredOn(s -> s.getNodeType() == NodeType.CONDITION)
			.allSatisfy(s -> assertThat(s.getYesStepId()).isEqualTo(end.getStepId()));
		assertThat(found).filteredOn(s -> s.getNodeType() == NodeType.END)
			.allSatisfy(s -> assertThat(s.getNextStepId()).isNull());
	}

	@Test
	void config가_null인_노드도_기본값_빈_객체로_저장된다() {
		WorkflowStep end = step(NodeType.END, null);

		workflowStepMapper.insertBatch(List.of(end));

		assertThat(workflowStepMapper.findByCampaignId(campaignId)).extracting(WorkflowStep::getConfigJson)
			.containsExactly("{}");
	}
}
