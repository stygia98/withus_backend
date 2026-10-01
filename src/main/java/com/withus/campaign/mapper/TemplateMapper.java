package com.withus.campaign.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.withus.campaign.domain.Template;

@Mapper
public interface TemplateMapper {

	/** channel 이 null 이면 전체 채널 */
	List<Template> findList(@Param("channel") String channel, @Param("offset") int offset, @Param("limit") int limit);

	long count(@Param("channel") String channel);

	Template findById(long templateId);

	void insert(Template template);

	void update(Template template);

	void delete(long templateId);

	/** 수정 차단용: SCHEDULED·ACTIVE·PAUSED 캠페인(일회성 직접 참조 또는 워크플로우 SEND 노드)이 쓰는 중인가 */
	boolean existsInUseByStatus(long templateId);

	/** 삭제 차단용: 상태와 무관하게 campaign.template_id 또는 workflow_step.config_json 이 참조하는가 */
	boolean existsReferenced(long templateId);

	/** 일회성 캠페인의 템플릿 ID (SendDispatcher 발송용) */
	Long findTemplateIdByCampaignId(long campaignId);

	/** 워크플로우 SEND 노드의 템플릿 ID — config_json.templateId (SendDispatcher 발송용) */
	Long findTemplateIdByStepId(long stepId);
}
