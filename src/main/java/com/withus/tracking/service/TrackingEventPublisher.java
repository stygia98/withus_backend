package com.withus.tracking.service;

import java.time.OffsetDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.withus.tracking.config.TrackingAsyncConfig;

/**
 * 추적 이벤트를 비동기로 저장한다. 저장에 실패해도 호출한 쪽(픽셀·리다이렉트 응답)에는 영향이 없도록
 * 예외를 로그로만 남기고 삼킨다 (PRD 8.1).
 */
@Component
public class TrackingEventPublisher {

	private static final Logger log = LoggerFactory.getLogger(TrackingEventPublisher.class);

	private final TrackingEventService trackingEventService;

	public TrackingEventPublisher(TrackingEventService trackingEventService) {
		this.trackingEventService = trackingEventService;
	}

	@Async(TrackingAsyncConfig.EXECUTOR)
	public void open(String token, String userAgent, String ip, OffsetDateTime occurredAt) {
		try {
			trackingEventService.recordOpen(token, userAgent, ip, occurredAt);
		} catch (RuntimeException e) {
			log.warn("오픈 이벤트 저장 실패", e);
		}
	}

	@Async(TrackingAsyncConfig.EXECUTOR)
	public void click(String token, long linkId, String userAgent, String ip, OffsetDateTime occurredAt) {
		try {
			trackingEventService.recordClick(token, linkId, userAgent, ip, occurredAt);
		} catch (RuntimeException e) {
			log.warn("클릭 이벤트 저장 실패 linkId={}", linkId, e);
		}
	}
}
