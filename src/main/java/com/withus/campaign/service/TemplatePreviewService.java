package com.withus.campaign.service;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.withus.campaign.domain.CustomerPlaceholderSource;
import com.withus.campaign.domain.Template;
import com.withus.campaign.dto.TemplatePreviewResponse;
import com.withus.campaign.dto.TemplatePreviewResponse.DefaultValueCount;
import com.withus.campaign.mapper.SendLogMapper;
import com.withus.common.domain.Channel;
import com.withus.common.exception.BusinessException;
import com.withus.common.exception.CommonErrorCode;
import com.withus.common.render.PlaceholderRenderer;
import com.withus.segment.service.SegmentService;

/**
 * 템플릿 렌더링 미리보기 (API_SPEC 5장, PRD F-04). 실제 발송과 같은 치환·광고 문구 규칙을 쓰되
 * 쿠폰 발급과 추적 링크 치환은 하지 않고, 쿠폰·수신거부 링크는 동작하지 않는 예시 주소로 채운다.
 */
@Service
public class TemplatePreviewService {

	private static final int CHUNK_SIZE = 500;
	private static final Charset SMS_CHARSET = Charset.forName("EUC-KR");

	private final TemplateService templateService;
	private final SendLogMapper sendLogMapper;
	private final SegmentService segmentService;
	private final PlaceholderRenderer placeholderRenderer;
	private final AdCopyInserter adCopyInserter;
	private final String trackingBaseUrl;

	public TemplatePreviewService(TemplateService templateService, SendLogMapper sendLogMapper,
			SegmentService segmentService, PlaceholderRenderer placeholderRenderer, AdCopyInserter adCopyInserter,
			@Value("${withus.tracking.base-url}") String trackingBaseUrl) {
		this.templateService = templateService;
		this.sendLogMapper = sendLogMapper;
		this.segmentService = segmentService;
		this.placeholderRenderer = placeholderRenderer;
		this.adCopyInserter = adCopyInserter;
		this.trackingBaseUrl = trackingBaseUrl;
	}

	public TemplatePreviewResponse preview(long templateId, long sampleCustomerId, Long segmentId) {
		Template template = templateService.getOrThrow(templateId);
		CustomerPlaceholderSource sample = sendLogMapper.findPlaceholderSource(sampleCustomerId);
		if (sample == null) {
			throw new BusinessException(CommonErrorCode.COMMON_NOT_FOUND, "미리보기용 고객을 찾을 수 없습니다.", null);
		}
		Map<String, String> values = MessageComposer.placeholderValues(sample, exampleCouponUrl());
		boolean isAd = template.isAd();

		TemplatePreviewResponse.DefaultValueCount counts = segmentId == null ? null
			: countDefaults(template, segmentId);

		if (template.getChannel() == Channel.EMAIL) {
			String subject = adCopyInserter.insertSubject(placeholderRenderer.render(template.getSubject(), values),
				isAd);
			String html = adCopyInserter.insertEmailBody(placeholderRenderer.renderHtml(template.getBody(), values),
				isAd, trackingBaseUrl + "/unsubscribe/example");
			return new TemplatePreviewResponse(subject, html, null, counts);
		}
		String text = adCopyInserter.insertSms(placeholderRenderer.render(template.getBody(), values), isAd);
		return new TemplatePreviewResponse(null, text, text.getBytes(SMS_CHARSET).length, counts);
	}

	private String exampleCouponUrl() {
		return trackingBaseUrl + "/c/example";
	}

	/** 세그먼트 대상을 500건씩 조회해 기본값으로 나갈 인원을 센다 (제목·본문 중 하나라도 기본값을 쓰면 1명) */
	private DefaultValueCount countDefaults(Template template, long segmentId) {
		List<Long> targetIds = segmentService.findTargetCustomers(segmentId);
		long usingDefault = 0;
		for (int from = 0; from < targetIds.size(); from += CHUNK_SIZE) {
			List<Long> chunk = targetIds.subList(from, Math.min(from + CHUNK_SIZE, targetIds.size()));
			for (CustomerPlaceholderSource source : sendLogMapper.findPlaceholderSources(chunk)) {
				Map<String, String> values = MessageComposer.placeholderValues(source, exampleCouponUrl());
				if (placeholderRenderer.usesDefault(template.getSubject(), values)
					|| placeholderRenderer.usesDefault(template.getBody(), values)) {
					usingDefault++;
				}
			}
		}
		return new DefaultValueCount(targetIds.size(), usingDefault);
	}
}
