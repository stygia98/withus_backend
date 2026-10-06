package com.withus.campaign.dto;

import jakarta.validation.constraints.NotBlank;

/** recipient 는 템플릿 채널에 따라 이메일 주소 또는 휴대폰 번호다. 정규화는 서비스가 한다 */
public record TemplateTestSendRequest(@NotBlank(message = "수신처를 입력하세요.") String recipient) {
}
