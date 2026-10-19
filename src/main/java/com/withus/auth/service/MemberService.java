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
import com.withus.auth.dto.PasswordResetRequest;
import com.withus.auth.mapper.MemberMapper;
import com.withus.common.exception.BusinessException;
import com.withus.common.exception.CommonErrorCode;
import com.withus.common.normalize.Emails;

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
		String email = Emails.normalize(req.email());
		if (memberMapper.existsEmail(email, null)) {
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

	/**
	 * 이름·이메일·역할·활성 여부 수정. 이름·이메일은 자기 계정도 바꿀 수 있다(로그인 토큰은 member_id 기준이라 이메일을 바꿔도
	 * 세션이 끊기지 않는다). 역할·활성 여부는 자기 계정이면 MEMBER_SELF_CHANGE — 이 규칙으로 마지막 OWNER 도 남는다
	 */
	@Transactional
	public MemberAdminResponse update(long memberId, MemberUpdateRequest req, long currentMemberId) {
		if (memberId == currentMemberId && (req.role() != null || req.active() != null)) {
			throw new BusinessException(AuthErrorCode.MEMBER_SELF_CHANGE);
		}
		String name = req.name() == null ? null : req.name().trim();
		if (name != null && name.isEmpty()) {
			throw new BusinessException(CommonErrorCode.COMMON_INVALID_INPUT, "이름을 입력하세요.", null);
		}
		String email = req.email() == null ? null : Emails.normalize(req.email());
		if (email != null && memberMapper.existsEmail(email, memberId)) {
			throw new BusinessException(AuthErrorCode.MEMBER_DUPLICATE_EMAIL);
		}
		String activeYn = req.active() == null ? null : req.active() ? "Y" : "N";
		try {
			if (memberMapper.updateMember(memberId, name, email, req.role(), activeYn) == 0) {
				throw new BusinessException(CommonErrorCode.COMMON_NOT_FOUND);
			}
		} catch (DuplicateKeyException e) {
			// 확인과 저장 사이에 같은 이메일이 먼저 들어온 경우
			throw new BusinessException(AuthErrorCode.MEMBER_DUPLICATE_EMAIL);
		}
		return MemberAdminResponse.from(memberMapper.findById(memberId));
	}

	/** 삭제 표시. 행은 남기고(캠페인 등 created_by 유지) 목록·로그인에서 뺀다. 자기 계정은 MEMBER_SELF_DELETE */
	@Transactional
	public void delete(long memberId, long currentMemberId) {
		if (memberId == currentMemberId) {
			throw new BusinessException(AuthErrorCode.MEMBER_SELF_DELETE);
		}
		if (memberMapper.markDeleted(memberId) == 0) {
			throw new BusinessException(CommonErrorCode.COMMON_NOT_FOUND);
		}
	}

	@Transactional(readOnly = true)
	public List<MemberAdminResponse> deletedList() {
		return memberMapper.findDeleted().stream().map(MemberAdminResponse::from).toList();
	}

	/** 삭제 표시를 지운다. 예전 비밀번호로 다시 로그인할 수 있다. 같은 이메일의 계정이 다시 생겼으면 MEMBER_DUPLICATE_EMAIL */
	@Transactional
	public MemberAdminResponse restore(long memberId) {
		try {
			if (memberMapper.restore(memberId) == 0) {
				throw new BusinessException(CommonErrorCode.COMMON_NOT_FOUND);
			}
		} catch (DuplicateKeyException e) {
			throw new BusinessException(AuthErrorCode.MEMBER_DUPLICATE_EMAIL,
				"같은 이메일의 계정이 있어 복구할 수 없어요. 그 계정의 이메일을 먼저 바꿔 주세요.", null);
		}
		return MemberAdminResponse.from(memberMapper.findById(memberId));
	}

	/**
	 * 비밀번호 재설정. 기존 비밀번호는 BCrypt 해시라 알 수 없고, 새 비밀번호로 덮어쓰기만 한다.
	 * Refresh 를 지워 기존 로그인을 끊는다. 본인은 AuthService.changePassword 로 바꾼다
	 */
	@Transactional
	public void resetPassword(long memberId, PasswordResetRequest req, long currentMemberId) {
		if (memberId == currentMemberId) {
			throw new BusinessException(AuthErrorCode.MEMBER_SELF_PASSWORD);
		}
		if (memberMapper.updatePassword(memberId, passwordEncoder.encode(req.newPassword())) == 0) {
			throw new BusinessException(CommonErrorCode.COMMON_NOT_FOUND);
		}
	}
}
