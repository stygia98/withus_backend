package com.withus.ai.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.withus.ai.domain.AiReport;

@Mapper
public interface AiReportMapper {

	/** 재생성할 때마다 새 행을 남긴다(이력). 새 report_id 를 돌려준다 */
	long insert(@Param("campaignId") long campaignId, @Param("reportType") String reportType,
		@Param("inputJson") String inputJson, @Param("content") String content, @Param("model") String model);

	/** 가장 최근 요약. 없으면 null */
	AiReport findLatest(@Param("campaignId") long campaignId, @Param("reportType") String reportType);
}
