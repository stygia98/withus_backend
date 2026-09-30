package com.withus.auth.security;

import com.withus.auth.domain.Role;

/**
 * 로그인한 사용자 (SecurityContext 의 principal)
 * 컨트롤러에서 @AuthenticationPrincipal AuthMember me 로 받아 me.memberId() 를 created_by 등에 쓴다
 */
public record AuthMember(long memberId, Role role) {
}
