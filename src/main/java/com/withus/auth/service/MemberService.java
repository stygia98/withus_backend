package com.withus.auth.service;

import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.withus.auth.domain.AuthErrorCode;
import com.withus.auth.domain.Member;
import com.withus.auth.dto.MemberAdminResponse;
import com.withus.auth.dto.MemberCreateRequest;
import com.withus.auth.dto.MemberUpdateRequest;
import com.withus.auth.mapper.MemberMapper;
import com.withus.common.exception.BusinessException;
import com.withus.common.exception.CommonErrorCode;

/**
 * 사용자 관리 (PRD 3장 "사용자 계정/권한 관리" OWNER 전용, 4장 /settings/users)
 * - 자기 계정의 역할·활성 여부는 바꿀 수 없다. 이 API 는 활성 OWNER 만 부르므로, 이 규칙 하나로
 *   "마지막 OWNER 가 사라지는" 경우도 생기지 않는다 (호출한 OWNER 자신이 남는다)
 * - 역할·활성 여부를 바꾸면 Refresh 토큰을 지운다. 재발급이 DB 역할을 다시 읽으므로
 *   늦어도 Access 만료(30분) 뒤에는 새 역할·비활성이 반영된다
 */
@Service
public class MemberService {

	private final MemberMapper memberMapper;
	private final PasswordEncoder passwordEncoder;

	public MemberService(MemberMapper memberMapper, PasswordEncoder passwordEncoder) {
		this.memberMapper = memberMapper;
		this.passwordEncoder = passwordEncoder;
	}

	@Transactional(readOnly = true)
	public List<MemberAdminResponse> list() {
		return memberMapper.findAll().stream().map(MemberAdminResponse::from).toList();
	}

	@Transactional
	public MemberAdminResponse create(MemberCreateRequest req) {
		String email = AuthService.normalizeEmail(req.email());
		if (memberMapper.existsEmail(email)) {
			throw new BusinessException(AuthErrorCode.MEMBER_DUPLICATE_EMAIL);
		}
		Member member = new Member();
		member.setEmail(email);
		member.setName(req.name().trim());
		member.setRole(req.role());
		member.setPassword(passwordEncoder.encode(req.password()));
		try {
			memberMapper.insert(member);
		} catch (DuplicateKeyException e) {
			// 확인과 저장 사이에 같은 이메일이 먼저 들어온 경우
			throw new BusinessException(AuthErrorCode.MEMBER_DUPLICATE_EMAIL);
		}
		return MemberAdminResponse.from(memberMapper.findById(member.getMemberId()));
	}

	@Transactional
	public MemberAdminResponse update(long memberId, MemberUpdateRequest req, long currentMemberId) {
		if (memberId == currentMemberId) {
			throw new BusinessException(AuthErrorCode.MEMBER_SELF_CHANGE);
		}
		String activeYn = req.active() == null ? null : req.active() ? "Y" : "N";
		if (memberMapper.updateRoleAndActive(memberId, req.role(), activeYn) == 0) {
			throw new BusinessException(CommonErrorCode.COMMON_NOT_FOUND);
		}
		return MemberAdminResponse.from(memberMapper.findById(memberId));
	}
}
