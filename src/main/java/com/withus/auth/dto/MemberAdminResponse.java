package com.withus.auth.dto;

import java.time.OffsetDateTime;

import com.withus.auth.domain.Member;
import com.withus.auth.domain.Role;

import io.swagger.v3.oas.annotations.media.Schema;

/** 사용자 관리 화면용. 비밀번호·토큰 해시는 넣지 않는다 */
public record MemberAdminResponse(long memberId, String email, String name, Role role, boolean active,
	@Schema(description = "삭제 표시 시각. 삭제하지 않은 계정은 null") OffsetDateTime deletedAt) {

	public static MemberAdminResponse from(Member member) {
		return new MemberAdminResponse(member.getMemberId(), member.getEmail(), member.getName(), member.getRole(),
			member.isActive(), member.getDeletedAt());
	}
}
