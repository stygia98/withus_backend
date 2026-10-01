package com.withus.tracking.service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.withus.tracking.config.TrackingProperties;
import com.withus.tracking.domain.TrackEvent;
import com.withus.tracking.domain.TrackedSend;
import com.withus.tracking.mapper.TrackEventMapper;

/**
 * 오픈·클릭 이벤트 저장과 클릭 리다이렉트 대상 결정 (PRD 8.1).
 * 동기 서비스다. 비동기 처리와 예외 삼키기는 {@link TrackingEventPublisher} 가 맡는다.
 */
@Service
public class TrackingEventService {

	private static final int USER_AGENT_MAX = 500;

	private final TrackEventMapper trackEventMapper;
	private final BotDetector botDetector;
	private final TrackingProperties properties;

	public TrackingEventService(TrackEventMapper trackEventMapper, BotDetector botDetector,
		TrackingProperties properties) {
		this.trackEventMapper = trackEventMapper;
		this.botDetector = botDetector;
		this.properties = properties;
	}

	/** 오픈 이벤트 저장. 없는 토큰이면 아무것도 저장하지 않는다 */
	@Transactional
	public void recordOpen(String token, String userAgent, String ip, OffsetDateTime occurredAt) {
		TrackedSend send = findSend(token);
		if (send == null) {
			return;
		}
		boolean bot = botDetector.isBotUserAgent(userAgent);
		trackEventMapper.insert(newEvent(send, TrackEvent.OPEN, null, userAgent, ip, bot, occurredAt));
	}

	/**
	 * 클릭 이벤트 저장. 없는 토큰·링크면 저장하지 않는다.
	 * 봇이 아닌 클릭인데 사람 OPEN 이 없으면 OPEN 도 함께 기록한다 (이미지 차단 환경 보정, PRD 8.1).
	 */
	@Transactional
	public void recordClick(String token, long linkId, String userAgent, String ip, OffsetDateTime occurredAt) {
		TrackedSend send = findSend(token);
		if (send == null || trackEventMapper.findOriginalUrl(linkId) == null) {
			return;
		}
		long sendLogId = send.getSendLogId();

		boolean bot = botDetector.isBotUserAgent(userAgent) || botDetector.isImmediateClick(send.getSentAt(), occurredAt);
		trackEventMapper.insert(newEvent(send, TrackEvent.CLICK, linkId, userAgent, ip, bot, occurredAt));

		if (!bot) {
			// 이 클릭으로 모든 링크가 짧은 시간 안에 눌렸다면, 앞선 클릭까지 소급해 봇으로 바꾼다
			OffsetDateTime since = occurredAt.minusSeconds(properties.burstClickWindowSeconds());
			int totalLinks = trackEventMapper.countLinksOfSameTemplate(linkId);
			int clickedLinks = trackEventMapper.countDistinctClickedLinksSince(sendLogId, since);
			if (botDetector.isBurstClick(totalLinks, clickedLinks)) {
				trackEventMapper.markBotSince(sendLogId, since);
				bot = true;
			}
		}

		if (!bot && !trackEventMapper.existsNotBotEvent(sendLogId, TrackEvent.OPEN)) {
			trackEventMapper.insert(newEvent(send, TrackEvent.OPEN, null, userAgent, ip, false, occurredAt));
		}
	}

	/**
	 * 클릭 리다이렉트 대상. 반드시 DB 에 등록된 원본 URL 만 쓰고(요청 값으로 이동하지 않는다),
	 * http/https 가 아니거나 없는 링크면 서비스 홈을 돌려준다 (오픈 리다이렉트 방지).
	 */
	@Transactional(readOnly = true)
	public String resolveRedirectUrl(long linkId) {
		String original = trackEventMapper.findOriginalUrl(linkId);
		return isHttpUrl(original) ? original : homeUrl();
	}

	public String homeUrl() {
		return properties.baseUrl();
	}

	private TrackedSend findSend(String token) {
		String normalized = normalizeToken(token);
		return normalized == null ? null : trackEventMapper.findSendByToken(normalized);
	}

	/** UUID 형식이 아니면 null. DB 에는 정규화된 소문자 UUID 문자열만 보낸다 */
	static String normalizeToken(String token) {
		if (token == null || token.length() != 36) {
			return null;
		}
		try {
			return UUID.fromString(token).toString();
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static boolean isHttpUrl(String url) {
		if (url == null || url.isBlank()) {
			return false;
		}
		try {
			String scheme = URI.create(url.trim()).getScheme();
			return scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"));
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	private TrackEvent newEvent(TrackedSend send, String type, Long linkId, String userAgent, String ip, boolean bot,
		OffsetDateTime occurredAt) {
		return TrackEvent.builder()
			.sendLogId(send.getSendLogId())
			.eventType(type)
			.linkId(linkId)
			.userAgent(truncate(userAgent))
			.ipHash(hashIp(ip))
			.botYn(bot ? "Y" : "N")
			.occurredAt(occurredAt)
			.build();
	}

	private static String truncate(String userAgent) {
		if (userAgent == null) {
			return null;
		}
		return userAgent.length() <= USER_AGENT_MAX ? userAgent : userAgent.substring(0, USER_AGENT_MAX);
	}

	/** IP 원문은 저장하지 않는다. SHA-256(솔트:IP) hex 64자 */
	private String hashIp(String ip) {
		if (ip == null || ip.isBlank()) {
			return null;
		}
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest((properties.ipSalt() + ":" + ip.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 을 사용할 수 없습니다", e);
		}
	}
}
