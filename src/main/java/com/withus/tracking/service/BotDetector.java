package com.withus.tracking.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.withus.tracking.config.TrackingProperties;

/**
 * 봇 이벤트 판정 (PRD 8.1). 판정 기준은 대시보드 수치에 직접 영향을 주므로 W3에 실제 메일로 검증한다.
 * 순수 판정만 한다 — DB 조회 결과(링크 수·클릭 수)는 호출하는 쪽이 넘겨 준다.
 */
@Component
public class BotDetector {

	private final TrackingProperties properties;

	public BotDetector(TrackingProperties properties) {
		this.properties = properties;
	}

	/**
	 * 규칙 2: 알려진 보안 스캐너·봇 User-Agent. 키워드는 대소문자를 무시한 <b>부분 일치</b>다
	 * (Googlebot·GOOGLEBOT·AhrefsBot·crawlers 모두 봇).
	 * 키워드를 우연히 포함하는 사람 기기명(예: Android 의 CUBOT)은 예외 목록에 두고, 판정 전에 UA 에서 지운다 —
	 * 대소문자 모양으로 추측하지 않고 확인된 기기명만 뺀다 (PR #38 리뷰).
	 * 비교는 소문자로 바꾼 문자열 하나로만 한다(원본과 길이가 다를 수 있어 위치를 섞어 쓰지 않는다).
	 */
	public boolean isBotUserAgent(String userAgent) {
		if (userAgent == null || userAgent.isBlank()) {
			return false;
		}
		String lower = userAgent.toLowerCase(Locale.ROOT);
		for (String allowed : normalized(properties.botUserAgentAllowList())) {
			lower = lower.replace(allowed, " ");
		}
		String withoutAllowed = lower;
		return normalized(properties.botUserAgentKeywords()).stream().anyMatch(withoutAllowed::contains);
	}

	private static List<String> normalized(List<String> values) {
		return values.stream()
			.map(value -> value.trim().toLowerCase(Locale.ROOT))
			.filter(value -> !value.isEmpty())
			.toList();
	}

	/**
	 * 규칙 1: 발송 후 설정 시간(기본 10초) 이내의 클릭. 경계값(정확히 N초)은 봇이다.
	 * 실제 발송 시각을 아직 모르면(null) 이 규칙은 적용하지 않는다.
	 */
	public boolean isImmediateClick(OffsetDateTime sentAt, OffsetDateTime clickedAt) {
		if (sentAt == null) {
			return false;
		}
		return Duration.between(sentAt, clickedAt).compareTo(Duration.ofSeconds(properties.botClickSeconds())) <= 0;
	}

	/**
	 * 규칙 3: 한 발송 건의 모든 추적 링크가 짧은 시간 안에 클릭됨.
	 * 링크가 1개뿐이면 "전부 클릭"이 항상 참이라 사람 클릭까지 봇이 되므로 링크가 2개 이상일 때만 적용한다.
	 */
	public boolean isBurstClick(int totalLinks, int distinctLinksClickedInWindow) {
		return totalLinks >= 2 && distinctLinksClickedInWindow >= totalLinks;
	}
}
