package com.withus.customer.domain;

/**
 * 논리 삭제된 고객 (API_SPEC 3장 DELETE /customers/{id}: 진행 중 인스턴스 CANCELLED)
 * 삭제 트랜잭션 안에서 발행되므로, 팀원2 워크플로우가 @EventListener 로 같은 트랜잭션에서 인스턴스를 취소한다
 */
public record CustomerDeletedEvent(long customerId) {
}
