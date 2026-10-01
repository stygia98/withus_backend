package com.withus.tracking.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.withus.tracking.domain.TrackLink;
import com.withus.tracking.domain.TrackingTarget;

@Mapper
public interface TrackLinkMapper {

	/** 발송 건의 추적 토큰과 템플릿 (campaign·workflow_step·template 은 팀원2 테이블, SELECT 만). 없는 발송 건이면 null */
	TrackingTarget findTarget(@Param("sendLogId") long sendLogId);

	/** 템플릿 원문 HTML (팀원2 테이블, SELECT 만) */
	String findTemplateBody(@Param("templateId") long templateId);

	List<TrackLink> findLinks(@Param("templateId") long templateId);

	/** 이미 있으면 아무것도 하지 않는다 (uq_track_link_template_url) */
	void insertIfAbsent(@Param("templateId") long templateId, @Param("originalUrl") String originalUrl,
		@Param("linkOrder") int linkOrder);
}
