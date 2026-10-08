package com.withus.customer.domain;

/**
 * 개별 등록된 고객 (PRD 6.2 CUSTOMER_REGISTERED 트리거). 팀원2 워크플로우가 @EventListener 로 받아 트리거를 판정한다
 * 등록 트랜잭션 안에서 발행된다. 업로드로 등록된 고객은 발행하지 않는다 (PRD F-01: 대량 발송 사고 방지)
 */
public record CustomerRegisteredEvent(long customerId) {
}
