package com.withus.tracking.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * withus.tracking.* (PRD 8.1)
 *
 * @param baseUrl                 추적 링크 기준 주소. 없는 링크의 리다이렉트 대상(서비스 홈)으로도 쓴다
 * @param botClickSeconds         발송 후 이 시간(초) 이내의 클릭은 봇으로 본다
 * @param botUserAgentKeywords    봇 User-Agent 키워드(대소문자 무시, 부분 일치)
 * @param botUserAgentAllowList   키워드를 우연히 포함하는 사람 기기 문자열(대소문자 무시). 키워드 판정 전에 UA 에서 지운다 (예: cubot)
 * @param ipSalt                  IP 해시 솔트. 환경변수 TRACKING_IP_SALT (비밀값, 커밋 금지)
 * @param burstClickWindowSeconds 한 발송 건의 모든 링크가 이 시간(초) 안에 클릭되면 봇으로 본다
 */
@ConfigurationProperties("withus.tracking")
public record TrackingProperties(String baseUrl, int botClickSeconds, List<String> botUserAgentKeywords,
	String ipSalt, int burstClickWindowSeconds, List<String> botUserAgentAllowList) {

	public TrackingProperties {
		botUserAgentKeywords = botUserAgentKeywords == null ? List.of() : List.copyOf(botUserAgentKeywords);
		ipSalt = ipSalt == null ? "" : ipSalt;
		botUserAgentAllowList = botUserAgentAllowList == null ? List.of() : List.copyOf(botUserAgentAllowList);
	}
}
