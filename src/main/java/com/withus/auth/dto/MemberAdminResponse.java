package com.withus.auth.dto;

import com.withus.auth.domain.Member;
import com.withus.auth.domain.Role;

/** 사용자 관리 화면용. 비밀번호·토큰 해시는 넣지 않는다 */
public record MemberAdminResponse(long memberId, String email, String name, Role role, boolean active) {

	public static MemberAdminResponse from(Member member) {
		return new MemberAdminResponse(member.getMemberId(), member.getEmail(), member.getName(), member.getRole(),
			member.isActive());
	}
}
