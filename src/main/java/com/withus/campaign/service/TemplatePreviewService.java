package com.withus.campaign.service;

import java.util.regex.Pattern;
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
	private static final int SMS_BYTE_LIMIT = 90;
	// 기본값 문법 {{name|고객}} 이 하나도 없으면 고객별 조회 없이 기본값 인원이 0 이다
	private static final Pattern HAS_DEFAULT = Pattern.compile("\\{\\{[^}]*\\|");
	// STAFF 용 고정 샘플 — 실제 고객 값이 아니다
	private static final CustomerPlaceholderSource FIXED_SAMPLE =
		new CustomerPlaceholderSource("홍길동", "sample@example.com", "SEOUL", 100000L);

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

	/** @param canReadCustomer 고객 조회 권한(OWNER·MANAGER). false 면 sampleCustomerId 를 무시하고 고정 샘플 값을 쓴다 */
	public TemplatePreviewResponse preview(long templateId, Long sampleCustomerId, Long segmentId,
			boolean canReadCustomer) {
		Template template = templateService.getOrThrow(templateId);
		CustomerPlaceholderSource sample = FIXED_SAMPLE;
		if (canReadCustomer && sampleCustomerId != null) {
			sample = sendLogMapper.findPreviewSource(sampleCustomerId);
			if (sample == null) {
				throw new BusinessException(CommonErrorCode.COMMON_NOT_FOUND, "미리보기용 고객을 찾을 수 없습니다.", null);
			}
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
			return new TemplatePreviewResponse(subject, html, null, null, null, counts);
		}
		String text = adCopyInserter.insertSms(placeholderRenderer.render(template.getBody(), values), isAd);
		int bytes = smsBytes(text);
		return new TemplatePreviewResponse(null, null, text, bytes, bytes > SMS_BYTE_LIMIT ? "LMS" : "SMS", counts);
	}

	/**
	 * 통신사 기준 바이트: ASCII 1바이트, 그 외(한글·확장 한글·이모지 등) 2바이트. Java 의 EUC-KR 은 완성형 2,350자만 인코딩해
	 * 확장 한글·이모지를 '?' 1바이트로 세어 LMS 과금을 놓치므로 쓰지 않는다
	 */
	public static int smsBytes(String text) {
		return text.codePoints().map(cp -> cp < 128 ? 1 : 2).sum();
	}

	private String exampleCouponUrl() {
		return trackingBaseUrl + "/c/example";
	}

	/** 세그먼트 대상을 500건씩 조회해 기본값으로 나갈 인원을 센다 (제목·본문 중 하나라도 기본값을 쓰면 1명) */
	private DefaultValueCount countDefaults(Template template, long segmentId) {
		List<Long> targetIds = segmentService.findTargetCustomers(segmentId);
		boolean hasDefault = HAS_DEFAULT.matcher(String.valueOf(template.getSubject())).find()
			|| HAS_DEFAULT.matcher(String.valueOf(template.getBody())).find();
		if (!hasDefault) {
			return new DefaultValueCount(targetIds.size(), 0);
		}
		long total = 0;
		long usingDefault = 0;
		for (int from = 0; from < targetIds.size(); from += CHUNK_SIZE) {
			List<Long> chunk = targetIds.subList(from, Math.min(from + CHUNK_SIZE, targetIds.size()));
			for (CustomerPlaceholderSource source : sendLogMapper.findPlaceholderSources(chunk)) {
				total++; // 삭제된 고객은 조회에서 빠지므로 실제로 센 행 수가 total 이다
				Map<String, String> values = MessageComposer.placeholderValues(source, exampleCouponUrl());
				if (placeholderRenderer.usesDefault(template.getSubject(), values)
					|| placeholderRenderer.usesDefault(template.getBody(), values)) {
					usingDefault++;
				}
			}
		}
		return new DefaultValueCount(total, usingDefault);
	}
}
