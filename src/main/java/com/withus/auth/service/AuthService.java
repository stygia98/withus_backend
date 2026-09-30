package com.withus.auth.service;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.withus.auth.domain.AuthErrorCode;
import com.withus.auth.domain.Member;
import com.withus.auth.mapper.MemberMapper;
import com.withus.auth.security.JwtProvider;
import com.withus.common.exception.BusinessException;
import com.withus.common.exception.CommonErrorCode;

import io.jsonwebtoken.JwtException;

/**
 * 로그인·재발급·로그아웃 (API_SPEC 2장, PRD 9장)
 * 로그인 실패 기록은 예외를 던진 뒤에도 남아야 하므로 이 클래스는 트랜잭션으로 묶지 않는다 (각 UPDATE 가 즉시 커밋)
 */
@Service
public class AuthService {

	private final MemberMapper memberMapper;
	private final PasswordEncoder passwordEncoder;
	private final JwtProvider jwtProvider;
	/** 없는 이메일에도 비밀번호 비교를 한 번 수행해 응답 시간으로 계정 존재가 드러나지 않게 한다 */
	private final String dummyHash;

	public AuthService(MemberMapper memberMapper, PasswordEncoder passwordEncoder, JwtProvider jwtProvider) {
		this.memberMapper = memberMapper;
		this.passwordEncoder = passwordEncoder;
		this.jwtProvider = jwtProvider;
		this.dummyHash = passwordEncoder.encode("withus-dummy-password");
	}

	public record Tokens(Member member, String accessToken, String refreshToken) {
	}

	public Tokens login(String rawEmail, String password) {
		Member member = memberMapper.findByEmail(normalizeEmail(rawEmail));
		if (member == null) {
			passwordEncoder.matches(password, dummyHash);
			throw new BusinessException(AuthErrorCode.AUTH_INVALID_CREDENTIALS);
		}
		if (member.isLocked(OffsetDateTime.now())) {
			throw new BusinessException(AuthErrorCode.AUTH_ACCOUNT_LOCKED, AuthErrorCode.AUTH_ACCOUNT_LOCKED.message(),
				Map.of("lockedUntil", member.getLockedUntil().toString()));
		}
		if (!passwordEncoder.matches(password, member.getPassword())) {
			memberMapper.recordLoginFailure(member.getMemberId());
			throw new BusinessException(AuthErrorCode.AUTH_INVALID_CREDENTIALS);
		}
		if (!member.isActive()) {
			throw new BusinessException(AuthErrorCode.AUTH_ACCOUNT_INACTIVE);
		}

		String refreshToken = jwtProvider.createRefreshToken(member.getMemberId());
		memberMapper.recordLoginSuccess(member.getMemberId(), JwtProvider.hash(refreshToken));
		return new Tokens(member, jwtProvider.createAccessToken(member.getMemberId(), member.getRole()), refreshToken);
	}

	/** Refresh 토큰으로 Access 재발급, Refresh 도 교체한다. 이전 Refresh 는 즉시 무효 */
	public Tokens refresh(String refreshToken) {
		if (refreshToken == null) {
			throw new BusinessException(AuthErrorCode.AUTH_UNAUTHORIZED);
		}
		long memberId;
		try {
			memberId = jwtProvider.parseRefreshToken(refreshToken);
		} catch (JwtException | IllegalArgumentException e) {
			throw new BusinessException(AuthErrorCode.AUTH_UNAUTHORIZED);
		}
		Member member = memberMapper.findById(memberId);
		if (member == null || !member.isActive()) {
			throw new BusinessException(AuthErrorCode.AUTH_UNAUTHORIZED);
		}
		String newRefresh = jwtProvider.createRefreshToken(memberId);
		int updated = memberMapper.rotateRefreshToken(memberId, JwtProvider.hash(refreshToken), JwtProvider.hash(newRefresh));
		if (updated == 0) {
			// 이미 교체됐거나 로그아웃·다른 기기 로그인으로 무효화된 토큰
			throw new BusinessException(AuthErrorCode.AUTH_UNAUTHORIZED);
		}
		return new Tokens(member, jwtProvider.createAccessToken(memberId, member.getRole()), newRefresh);
	}

	public void logout(long memberId) {
		memberMapper.clearRefreshToken(memberId);
	}

	public Member me(long memberId) {
		Member member = memberMapper.findById(memberId);
		if (member == null) {
			throw new BusinessException(CommonErrorCode.COMMON_NOT_FOUND);
		}
		return member;
	}

	// ponytail: 팀원1의 정규화 유틸(F-01)이 생기면 그것으로 교체
	static String normalizeEmail(String email) {
		return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
	}
}
