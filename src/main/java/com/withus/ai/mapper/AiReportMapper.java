package com.withus.ai.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.withus.ai.domain.AiReport;

@Mapper
public interface AiReportMapper {

	/**
	 * 재생성할 때마다 새 행을 남기고, 같은 캠페인·유형은 최근 keep 건만 남긴다(한 문장). 새 report_id 를 돌려준다.
	 * ponytail: 같은 캠페인을 동시에 재생성하면 잠깐 keep + 1 건이 될 수 있다. 다음 저장 때 정리되며, 정확히 맞춰야 하면 캠페인 행 잠금을 건다
	 */
	long insertKeepingLatest(@Param("campaignId") long campaignId, @Param("reportType") String reportType,
		@Param("inputJson") String inputJson, @Param("content") String content, @Param("model") String model,
		@Param("keep") int keep);

	/** 가장 최근 요약. 없으면 null */
	AiReport findLatest(@Param("campaignId") long campaignId, @Param("reportType") String reportType);
}
