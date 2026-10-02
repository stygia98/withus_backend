package com.withus.tracking.service;

import java.time.Duration;
import java.time.OffsetDateTime;
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
	 * 규칙 2: 알려진 보안 스캐너·봇 User-Agent. 키워드는 대소문자를 무시하되 <b>단어 끝</b>에 올 때만 맞는 것으로 본다.
	 * <ul>
	 * <li>맞음: Googlebot, bingbot, AhrefsBot, Slackbot-LinkExpanding, SecurityScanner/1.0, SOME-CRAWLER, LinkPreview</li>
	 * <li>안 맞음: 뒤에 소문자가 이어지는 경우(Robotics, Bottle), 전부 대문자인 단어의 일부(기기명 CUBOT X30)</li>
	 * </ul>
	 * 단순 부분 일치는 사람 기기 UA 를 봇으로 지워 오픈·클릭률과 워크플로우 분기를 틀리게 한다(PR #30 리뷰).
	 * 단어 전체 일치만 보면 Googlebot 같은 실제 봇을 놓치므로 "단어 끝" 기준을 쓴다.
	 */
	public boolean isBotUserAgent(String userAgent) {
		if (userAgent == null || userAgent.isBlank()) {
			return false;
		}
		String lower = userAgent.toLowerCase(Locale.ROOT);
		return properties.botUserAgentKeywords().stream()
			.map(keyword -> keyword.trim().toLowerCase(Locale.ROOT))
			.filter(keyword -> !keyword.isEmpty())
			.anyMatch(keyword -> containsAtWordEnd(userAgent, lower, keyword));
	}

	private static boolean containsAtWordEnd(String userAgent, String lower, String keyword) {
		for (int at = lower.indexOf(keyword); at >= 0; at = lower.indexOf(keyword, at + 1)) {
			int end = at + keyword.length();
			boolean wordEnds = end == userAgent.length() || !Character.isLowerCase(userAgent.charAt(end));
			if (wordEnds && !insideUpperCaseWord(userAgent, at, end)) {
				return true;
			}
		}
		return false;
	}

	/** 맞은 부분이 전부 대문자이고 바로 앞도 대문자면 더 긴 대문자 단어(CUBOT)의 일부다 */
	private static boolean insideUpperCaseWord(String userAgent, int start, int end) {
		if (start == 0 || !Character.isUpperCase(userAgent.charAt(start - 1))) {
			return false;
		}
		return userAgent.substring(start, end).chars().noneMatch(Character::isLowerCase);
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
