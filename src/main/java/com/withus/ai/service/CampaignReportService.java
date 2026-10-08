package com.withus.ai.service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.withus.ai.domain.AiErrorCode;
import com.withus.ai.domain.AiReport;
import com.withus.ai.dto.CampaignReportResponse;
import com.withus.ai.mapper.AiReportMapper;
import com.withus.common.exception.BusinessException;
import com.withus.tracking.dto.CampaignAnalyticsResponse;
import com.withus.tracking.dto.SendKpi;
import com.withus.tracking.service.DashboardService;

import tools.jackson.databind.ObjectMapper;

/**
 * AI-03 캠페인 성과 요약 (PRD 5.3). 집계 지표(JSON)만 LLM 에 보내고 5문장 이내 요약을 ai_report 에 저장한다.
 * 지표는 대시보드 캠페인 성과(F-09)와 같은 정의를 그대로 쓴다 — 화면 숫자와 요약 숫자가 어긋나지 않게.
 *
 * <p>순서: 지표 조회 → LLM 호출(트랜잭션 밖) → 저장. 저장은 INSERT 한 문장이라 별도 트랜잭션이 필요 없다.
 */
@Service
public class CampaignReportService {

	private static final Logger log = LoggerFactory.getLogger(CampaignReportService.class);

	static final int MAX_SENTENCES = 5;
	/** 성공 발송이 없으면 요약할 성과가 없으므로 LLM 을 부르지 않는다 (무료 등급 한도 절약) */
	static final String NO_LLM_MODEL = "none";
	static final String NO_DATA_CONTENT = "아직 성공한 발송이 없어 요약할 성과가 없습니다.";

	private static final String SYSTEM_INSTRUCTION = """
		당신은 CRM 마케팅 성과 분석가다. 주어진 캠페인 집계 지표(JSON)를 관리자에게 보고하는 한국어 요약을 쓴다.
		- 5문장 이내의 평문으로 쓴다. 마크다운·목록·제목을 쓰지 않는다.
		- 비율 값은 0~1 소수이므로 백분율(소수 첫째 자리)로 바꿔 쓴다. 예: 0.3120 → 31.2%
		- 지표 정의: 성공률 = 성공/시도, 오픈율·클릭률·전환율(쿠폰 사용) = 고유 고객 수/성공 발송. 봇·테스트 발송은 이미 빠져 있다.
		- 클릭률·전환율도 오픈한 고객이 아니라 성공 발송 전체 대비 비율이다. "오픈한 고객 중 몇 %"처럼 쓰지 않는다.
		- 주어진 숫자만 쓰고, 업계 평균이나 원인을 지어내지 않는다. 개선 제안은 1문장까지만 쓴다.
		""";

	private final DashboardService dashboardService;
	private final AiReportMapper aiReportMapper;
	private final LlmClient llmClient;
	private final ObjectMapper objectMapper;

	public CampaignReportService(DashboardService dashboardService, AiReportMapper aiReportMapper, LlmClient llmClient,
		ObjectMapper objectMapper) {
		this.dashboardService = dashboardService;
		this.aiReportMapper = aiReportMapper;
		this.llmClient = llmClient;
		this.objectMapper = objectMapper;
	}

	/** 생성·재생성 모두 새 요약을 만든다. 없는 캠페인은 COMMON_NOT_FOUND(404) */
	public CampaignReportResponse generate(long campaignId) {
		CampaignAnalyticsResponse analytics = dashboardService.campaign(campaignId);
		String inputJson = objectMapper.writeValueAsString(input(analytics));

		String content;
		String model;
		if (analytics.kpi().sent() == 0) {
			content = NO_DATA_CONTENT;
			model = NO_LLM_MODEL;
		} else {
			LlmResponse response = llmClient.generate(new LlmRequest(SYSTEM_INSTRUCTION, inputJson, false, 0.3, 1024,
				mockSummary(analytics)));
			if (response.truncated()) {
				log.warn("AI-03 요약이 출력 한도에서 잘렸습니다 model={}", response.model());
				throw new BusinessException(AiErrorCode.AI_UNAVAILABLE);
			}
			content = limitSentences(response.text());
			if (content.isEmpty()) {
				throw new BusinessException(AiErrorCode.AI_UNAVAILABLE);
			}
			model = response.model();
		}
		aiReportMapper.insert(campaignId, AiReport.CAMPAIGN_SUMMARY, inputJson, content, model);
		return latest(campaignId);
	}

	/** 아직 만든 요약이 없으면 null (화면은 "요약 생성" 버튼을 보여 준다) */
	public CampaignReportResponse latest(long campaignId) {
		AiReport report = aiReportMapper.findLatest(campaignId, AiReport.CAMPAIGN_SUMMARY);
		if (report == null) {
			return null;
		}
		return new CampaignReportResponse(report.getReportId(), report.getCampaignId(), report.getContent(),
			report.getModel(), objectMapper.readTree(report.getInputJson()), report.getCreatedAt());
	}

	/** LLM 에 보내고 ai_report.input_json 에 남기는 값. 집계 지표만 — 고객 개인정보 없음 (CLAUDE.md 6장 12번) */
	private static Map<String, Object> input(CampaignAnalyticsResponse a) {
		SendKpi k = a.kpi();
		Map<String, Object> kpi = new LinkedHashMap<>();
		kpi.put("attempted", k.attempted());
		kpi.put("sent", k.sent());
		kpi.put("successRate", k.successRate());
		kpi.put("uniqueOpens", k.uniqueOpens());
		kpi.put("openRate", k.openRate());
		kpi.put("uniqueClicks", k.uniqueClicks());
		kpi.put("clickRate", k.clickRate());
		kpi.put("couponUsed", k.couponUsed());
		kpi.put("conversionRate", k.conversionRate());
		Map<String, Object> input = new LinkedHashMap<>();
		input.put("campaignName", a.name());
		input.put("kpi", kpi);
		return input;
	}

	/** 마크다운 기호·줄바꿈을 정리하고 5문장까지만 남긴다 */
	static String limitSentences(String text) {
		if (text == null) {
			return "";
		}
		String plain = text.replaceAll("<[^>]{1,200}>", "")
			.replaceAll("(?m)^\\s*(?:[#>*-]+|\\d+[.)])\\s+", "")
			.replace("**", "")
			.replaceAll("\\s+", " ")
			.strip();
		if (plain.isEmpty()) {
			return "";
		}
		return Arrays.stream(plain.split("(?<=[.!?])\\s+"))
			.limit(MAX_SENTENCES)
			.collect(Collectors.joining(" "));
	}

	private static String mockSummary(CampaignAnalyticsResponse a) {
		SendKpi k = a.kpi();
		return "[MOCK] '%s' 캠페인은 %d건을 시도해 %d건 발송에 성공했습니다. 오픈율 %.1f%%, 클릭률 %.1f%%, 전환율 %.1f%%입니다."
			.formatted(a.name(), k.attempted(), k.sent(), k.openRate() * 100, k.clickRate() * 100,
				k.conversionRate() * 100);
	}
}
