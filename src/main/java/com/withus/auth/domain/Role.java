package com.withus.auth.domain;

/** 시스템 사용자 역할 (PRD 3장). @PreAuthorize 에서는 hasRole('OWNER') 형태로 쓴다 */
public enum Role {
	OWNER, MANAGER, STAFF
}
