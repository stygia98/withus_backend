package com.withus.campaign.dto;

/**
 * sampleCustomerId 는 OWNER·MANAGER 만 쓸 수 있다. STAFF 가 보내면 무시하고 고정 샘플 값으로 미리보기한다
 * (PRD 3장: STAFF 는 고객 조회 불가). segmentId 가 있으면 그 세그먼트 대상 중 기본값으로 나갈 인원도 계산한다
 */
public record TemplatePreviewRequest(Long sampleCustomerId, Long segmentId) {
}
