package com.withus.tracking.service;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.withus.tracking.domain.TrackLink;
import com.withus.tracking.domain.TrackingTarget;
import com.withus.tracking.mapper.TrackLinkMapper;

/**
 * 구간 간 연결 인터페이스 구현 (PRD 8.1, 발송 큐 Plan 9장 4번).
 * 발송 직전에 호출되며, 템플릿의 고정 링크를 track_link 에 자동 등록하고(처음 한 번) 본문 링크를 추적 URL 로 바꾼다.
 *
 * <ul>
 * <li>템플릿을 모르는 발송(TEST·NOTICE 등)은 링크를 그대로 두고 오픈 픽셀만 넣는다.</li>
 * <li>대량 발송에서 매번 템플릿을 다시 읽지 않도록 템플릿별 링크를 메모리에 둔다.
 *     템플릿이 수정되면(updated_at 변경) 다시 만든다.</li>
 * <li>외부 호출이 없는 짧은 DB 작업만 하므로 자체 트랜잭션으로 둔다.</li>
 * </ul>
 */
@Service
public class TrackingLinkServiceImpl implements TrackingLinkService {

	private static final Logger log = LoggerFactory.getLogger(TrackingLinkServiceImpl.class);

	private final TrackLinkMapper trackLinkMapper;
	private final TrackingHtmlRewriter rewriter;
	private final ConcurrentMap<Long, CachedLinks> cache = new ConcurrentHashMap<>();

	public TrackingLinkServiceImpl(TrackLinkMapper trackLinkMapper, TrackingHtmlRewriter rewriter) {
		this.trackLinkMapper = trackLinkMapper;
		this.rewriter = rewriter;
	}

	@Override
	@Transactional
	public String rewrite(String html, long sendLogId) {
		TrackingTarget target = trackLinkMapper.findTarget(sendLogId);
		if (target == null) {
			// 발송 건이 없으면 토큰도 없다. 호출 순서 오류이므로 본문은 그대로 돌려준다
			log.warn("추적 치환 대상 발송 건이 없습니다 sendLogId={}", sendLogId);
			return html;
		}
		if (target.getTemplateId() == null) {
			return rewriter.rewrite(html, target.getTrackingToken(), url -> null);
		}
		Map<String, Long> linkIds = linksOf(target.getTemplateId(), target.getTemplateUpdatedAt());
		return rewriter.rewrite(html, target.getTrackingToken(), linkIds::get);
	}

	/** 템플릿의 원본 URL → link_id. 캐시가 없거나 템플릿이 바뀌었으면 등록·조회해서 다시 만든다 */
	private Map<String, Long> linksOf(long templateId, OffsetDateTime templateUpdatedAt) {
		CachedLinks cached = cache.get(templateId);
		if (cached != null && Objects.equals(cached.templateUpdatedAt(), templateUpdatedAt)) {
			return cached.linkIds();
		}
		Map<String, Long> linkIds = registerAndLoad(templateId);
		putAfterCommit(templateId, new CachedLinks(templateUpdatedAt, linkIds));
		return linkIds;
	}

	/**
	 * 커밋이 확정된 뒤에만 캐시에 넣는다. 호출한 쪽 트랜잭션이 롤백되면 방금 등록한 link_id 가 DB 에 없는데
	 * 캐시에 남아, 이후 메일의 링크가 없는 ID 를 가리키게 되기 때문이다.
	 */
	private void putAfterCommit(long templateId, CachedLinks links) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			cache.put(templateId, links);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				cache.put(templateId, links);
			}
		});
	}

	private Map<String, Long> registerAndLoad(long templateId) {
		List<String> templateUrls = rewriter.templateLinkUrls(trackLinkMapper.findTemplateBody(templateId));
		Map<String, Long> linkIds = toMap(trackLinkMapper.findLinks(templateId));

		boolean inserted = false;
		for (int i = 0; i < templateUrls.size(); i++) {
			if (!linkIds.containsKey(templateUrls.get(i))) {
				// 동시에 다른 발송이 같은 링크를 넣어도 ON CONFLICT 로 한 행만 남는다
				trackLinkMapper.insertIfAbsent(templateId, templateUrls.get(i), i + 1);
				inserted = true;
			}
		}
		return inserted ? toMap(trackLinkMapper.findLinks(templateId)) : linkIds;
	}

	private static Map<String, Long> toMap(List<TrackLink> links) {
		Map<String, Long> map = new HashMap<>();
		for (TrackLink link : links) {
			map.putIfAbsent(link.getOriginalUrl(), link.getLinkId());
		}
		return Map.copyOf(map);
	}

	private record CachedLinks(OffsetDateTime templateUpdatedAt, Map<String, Long> linkIds) {
	}
}
