package com.withus.customer.dto;

import java.time.OffsetDateTime;

import com.withus.common.domain.Channel;
import com.withus.customer.domain.ConsentHistory;

import io.swagger.v3.oas.annotations.media.Schema;

/** 동의 이력 한 건. before 가 null 이면 최초 등록 */
public record ConsentHistoryResponse(
	long historyId, Channel channel, String before, String after,
	@Schema(description = "ADMIN, UPLOAD, UNSUBSCRIBE, BOUNCE, COMPLAINT") String source,
	String note, OffsetDateTime changedAt) {

	public static ConsentHistoryResponse of(ConsentHistory h) {
		return new ConsentHistoryResponse(h.getHistoryId(), h.getChannel(), h.getBeforeYn(), h.getAfterYn(),
			h.getSource(), h.getNote(), h.getChangedAt());
	}
}
