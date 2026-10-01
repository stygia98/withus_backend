package com.withus.auth.dto;

import com.withus.auth.domain.Role;

/** 역할·활성 여부 변경 (API_SPEC 2장 PATCH /members/{id}). 보내지 않은 값은 그대로 둔다 */
public record MemberUpdateRequest(Role role, Boolean active) {
}
