package com.withus.customer.dto;

import java.util.List;

import com.withus.common.domain.Channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** 수신거부 처리 (API_SPEC 8장 POST /public/unsubscribe/{token}) */
public record UnsubscribeRequest(
	@Schema(description = "EMAIL, SMS, ALL", example = "EMAIL") @NotNull(message = "채널을 고르세요.") Target channel) {

	public enum Target {
		EMAIL(List.of(Channel.EMAIL)), SMS(List.of(Channel.SMS)), ALL(List.of(Channel.EMAIL, Channel.SMS));

		private final List<Channel> channels;

		Target(List<Channel> channels) {
			this.channels = channels;
		}

		public List<Channel> channels() {
			return channels;
		}
	}
}
