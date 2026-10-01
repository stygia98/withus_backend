package com.withus.tracking.domain;

import java.time.OffsetDateTime;

import lombok.Getter;
import lombok.Setter;

/** 추적 토큰으로 찾은 발송 건 (send_log 중 추적에 필요한 열만) */
@Getter
@Setter
public class TrackedSend {

	private Long sendLogId;
	/** CAMPAIGN / NOTICE / TEST — NOTICE·TEST 는 저장하되 모든 지표에서 제외한다 (CLAUDE.md 6장 8번) */
	private String kind;
	/** 실제 발송 시각. 아직 기록 전이면 null */
	private OffsetDateTime sentAt;
}
