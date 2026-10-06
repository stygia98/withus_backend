package com.withus.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.withus.campaign.domain.Campaign;
import com.withus.campaign.domain.CampaignType;
import com.withus.campaign.domain.Template;
import com.withus.campaign.mapper.SendLogMapper;
import com.withus.campaign.mapper.TemplateMapper;
import com.withus.campaign.service.CampaignService;
import com.withus.common.exception.BusinessException;
import com.withus.workflow.domain.NodeType;
import com.withus.workflow.domain.WorkflowValidationResult;
import com.withus.workflow.domain.WorkflowCheck;
import com.withus.workflow.dto.WorkflowSaveRequest;
import com.withus.workflow.dto.WorkflowStepRequest;
import com.withus.workflow.mapper.WorkflowStepMapper;
import com.withus.workflow.service.WorkflowService;
import com.withus.workflow.service.WorkflowValidator;

import tools.jackson.databind.json.JsonMapper;

/** 없는 템플릿은 예외가 아니라 TEMPLATE_EXISTS 검사 결과로 보고된다 (PR #34 리뷰) — DB 없이 실행된다 */
class WorkflowServiceTemplateCheckTest {

	WorkflowStepMapper stepMapper = mock(WorkflowStepMapper.class);
	CampaignService campaignService = mock(CampaignService.class);
	TemplateMapper templateMapper = mock(TemplateMapper.class);
	WorkflowService service = new WorkflowService(stepMapper, campaignService, templateMapper,
		mock(SendLogMapper.class), new WorkflowValidator(), JsonMapper.builder().build());

	private WorkflowSaveRequest request() {
		return new WorkflowSaveRequest(List.of(
			new WorkflowStepRequest("t", NodeType.TRIGGER, Map.of("triggerType", "CUSTOMER_REGISTERED"), "s", null, null),
			new WorkflowStepRequest("s", NodeType.SEND_EMAIL, Map.of("templateId", 99), "e", null, null),
			new WorkflowStepRequest("e", NodeType.END, Map.of(), null, null, null)));
	}

	private void workflowCampaign() {
		Campaign campaign = new Campaign();
		campaign.setCampaignId(1L);
		campaign.setType(CampaignType.WORKFLOW);
		when(campaignService.getOrThrow(1L)).thenReturn(campaign);
	}

	@Test
	void 없는_템플릿은_예외가_아니라_실패한_검사로_보고된다() {
		workflowCampaign();
		when(templateMapper.findById(99L)).thenReturn(null);

		WorkflowValidationResult result = service.validateOnly(1L, request());

		assertThat(result.valid()).isFalse();
		assertThat(result.checks()).filteredOn(c -> c.code().equals("TEMPLATE_EXISTS"))
			.singleElement().satisfies(c -> {
				assertThat(c.passed()).isFalse();
				assertThat(c.message()).contains("99");
			});
		assertThat(result.checks()).filteredOn(WorkflowCheck::passed).as("나머지 구조 검사는 그대로 보고된다").isNotEmpty();
	}

	@Test
	void 템플릿이_있으면_TEMPLATE_EXISTS가_통과한다() {
		workflowCampaign();
		Template template = new Template();
		template.setSubject("제목");
		template.setBody("본문");
		when(templateMapper.findById(99L)).thenReturn(template);

		WorkflowValidationResult result = service.validateOnly(1L, request());

		assertThat(result.checks()).filteredOn(c -> c.code().equals("TEMPLATE_EXISTS"))
			.singleElement().satisfies(c -> assertThat(c.passed()).isTrue());
	}

	@Test
	void 저장은_없는_템플릿이면_구조_오류로_막고_아무것도_지우지_않는다() {
		workflowCampaign();
		when(stepMapper.lockCampaignStatus(1L)).thenReturn("DRAFT");
		when(templateMapper.findById(99L)).thenReturn(null);

		assertThatThrownBy(() -> service.save(1L, request())).isInstanceOf(BusinessException.class);

		verify(stepMapper, never()).deleteByCampaignId(anyLong());
	}
}
