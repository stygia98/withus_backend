package com.withus.tracking.dto;

import java.util.List;

import com.withus.tracking.domain.RecentEventRow;

/**
 * GET /dashboard/events — 최신순 이벤트. 다음 폴링에서 lastEventId 를 after 로 넘긴다.
 *
 * @param lastEventId 이번 응답의 가장 큰 이벤트 ID. 새 이벤트가 없으면 요청한 after 를 그대로 돌려준다
 */
public record RecentEventsResponse(List<RecentEventRow> events, Long lastEventId) {
}
