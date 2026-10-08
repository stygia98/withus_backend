package com.withus.customer.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.withus.common.domain.Channel;

import io.swagger.v3.oas.annotations.media.Schema;

/** 수신거부 처리 결과. 완료 화면에 채널과 처리 일시를 보여준다 (PRD F-08) */
public record UnsubscribeResponse(
	@Schema(description = "처리한 채널. 휴대폰이 없으면 SMS 는 빠진다") List<Channel> channels,
	@Schema(example = "2026-09-30T18:20:51+09:00") OffsetDateTime processedAt) {
}
