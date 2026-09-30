package com.withus.auth.dto;

import com.withus.auth.domain.Member;
import com.withus.auth.domain.Role;

public record MemberResponse(long memberId, String email, String name, Role role) {

	public static MemberResponse from(Member member) {
		return new MemberResponse(member.getMemberId(), member.getEmail(), member.getName(), member.getRole());
	}
}
