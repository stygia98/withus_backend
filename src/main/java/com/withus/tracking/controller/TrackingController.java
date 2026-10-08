package com.withus.tracking.controller;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.withus.tracking.service.TrackingEventPublisher;
import com.withus.tracking.service.TrackingEventService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 오픈·클릭 추적 (PRD 8.1, API_SPEC 9장). 인증 없이 호출되며, 처리에 실패해도 응답은 항상 정상이다.
 * 응답에 필요한 일(리다이렉트 대상 조회)만 동기로 하고, 이벤트 저장은 비동기로 넘긴다.
 */
@RestController
@RequestMapping("/t")
@Tag(name = "추적", description = "메일 오픈·클릭 추적 (인증 없음, 항상 정상 응답)")
public class TrackingController {

	private static final Logger log = LoggerFactory.getLogger(TrackingController.class);

	/** 1x1 투명 GIF */
	private static final byte[] PIXEL = Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7");

	private final TrackingEventPublisher publisher;
	private final TrackingEventService trackingEventService;

	public TrackingController(TrackingEventPublisher publisher, TrackingEventService trackingEventService) {
		this.publisher = publisher;
		this.trackingEventService = trackingEventService;
	}

	@Operation(summary = "오픈 추적 픽셀", description = "항상 200 + 1x1 투명 GIF. 없는 토큰이면 이벤트만 저장하지 않는다")
	@GetMapping("/o/{token}.gif")
	public ResponseEntity<byte[]> open(@PathVariable String token, HttpServletRequest request) {
		try {
			publisher.open(token, request.getHeader(HttpHeaders.USER_AGENT), request.getRemoteAddr(),
				OffsetDateTime.now());
		} catch (RuntimeException e) {
			log.warn("오픈 이벤트 접수 실패", e);
		}
		return ResponseEntity.ok()
			.contentType(MediaType.IMAGE_GIF)
			.cacheControl(CacheControl.noStore())
			.body(PIXEL);
	}

	@Operation(summary = "클릭 추적 리다이렉트",
		description = "항상 302. 등록된 원본 URL 로 이동하고, 없는 링크면 서비스 홈으로 이동한다 (요청 값으로는 이동하지 않음)")
	@GetMapping("/c/{token}/{linkId}")
	public ResponseEntity<Void> click(@PathVariable String token, @PathVariable String linkId,
		HttpServletRequest request) {
		Long id = parseLinkId(linkId);
		String target = trackingEventService.homeUrl();
		if (id != null) {
			try {
				publisher.click(token, id, request.getHeader(HttpHeaders.USER_AGENT), request.getRemoteAddr(),
					OffsetDateTime.now());
				target = trackingEventService.resolveRedirectUrl(id);
			} catch (RuntimeException e) {
				log.warn("클릭 처리 실패 linkId={}", id, e);
				target = trackingEventService.homeUrl();
			}
		}
		return ResponseEntity.status(HttpStatus.FOUND)
			.location(safeUri(target))
			.cacheControl(CacheControl.noStore())
			.build();
	}

	private static Long parseLinkId(String linkId) {
		try {
			return Long.valueOf(linkId);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private URI safeUri(String url) {
		try {
			return URI.create(url);
		} catch (IllegalArgumentException e) {
			return URI.create(trackingEventService.homeUrl());
		}
	}
}
