package com.withus.customer.dto;

import java.util.List;

import com.withus.common.domain.Channel;

import io.swagger.v3.oas.annotations.media.Schema;

/** 수신거부 확인 화면 정보 (API_SPEC 8장 GET /public/unsubscribe/{token}). 이메일·휴대폰 원문은 넣지 않는다 */
public record UnsubscribeInfoResponse(
	@Schema(description = "첫 글자만 남기고 마스킹. 이름이 없으면 null", example = "김**") String customerName,
	@Schema(description = "고를 수 있는 채널. 휴대폰이 없으면 EMAIL 만") List<Channel> channels,
	@Schema(description = "이미 수신거부(동의 N 또는 수신거부 목록)인 채널") List<Channel> unsubscribedChannels) {
}
