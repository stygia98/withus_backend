package com.withus.auth.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Component;

import com.withus.auth.domain.Role;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Access·Refresh JWT 발급·검증 (PRD 2.2, 9장)
 * Refresh 는 DB 에 해시만 저장하고 재발급마다 교체한다
 */
@Component
public class JwtProvider {

	private static final String CLAIM_TYPE = "typ";
	private static final String CLAIM_ROLE = "role";
	private static final String TYPE_ACCESS = "access";
	private static final String TYPE_REFRESH = "refresh";

	private final SecretKey key;
	private final JwtProperties props;

	public JwtProvider(JwtProperties props) {
		if (props.secret() == null || props.secret().length() < 32) {
			throw new IllegalStateException("JWT_SECRET 환경변수는 32자 이상이어야 합니다.");
		}
		this.key = Keys.hmacShaKeyFor(props.secret().getBytes(StandardCharsets.UTF_8));
		this.props = props;
	}

	public String createAccessToken(long memberId, Role role) {
		return Jwts.builder()
			.subject(String.valueOf(memberId))
			.claim(CLAIM_TYPE, TYPE_ACCESS)
			.claim(CLAIM_ROLE, role.name())
			.issuedAt(new Date())
			.expiration(expiry(props.accessTtl()))
			.signWith(key)
			.compact();
	}

	public String createRefreshToken(long memberId) {
		return Jwts.builder()
			.subject(String.valueOf(memberId))
			.claim(CLAIM_TYPE, TYPE_REFRESH)
			.id(UUID.randomUUID().toString())
			.issuedAt(new Date())
			.expiration(expiry(props.refreshTtl()))
			.signWith(key)
			.compact();
	}

	/** 만료면 ExpiredJwtException, 위조·형식 오류면 JwtException. Refresh 토큰을 Access 로 쓰면 거부 */
	public AuthMember parseAccessToken(String token) {
		Claims claims = parse(token, TYPE_ACCESS);
		return new AuthMember(Long.parseLong(claims.getSubject()), Role.valueOf(claims.get(CLAIM_ROLE, String.class)));
	}

	/** 서명·만료·용도만 확인한다. DB 해시 일치 확인은 AuthService 에서 */
	public long parseRefreshToken(String token) {
		return Long.parseLong(parse(token, TYPE_REFRESH).getSubject());
	}

	public Duration accessTtl() {
		return props.accessTtl();
	}

	public Duration refreshTtl() {
		return props.refreshTtl();
	}

	/** DB 저장용 SHA-256 해시 (원문 토큰은 저장하지 않음) */
	public static String hash(String token) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private Claims parse(String token, String expectedType) {
		Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
		if (!expectedType.equals(claims.get(CLAIM_TYPE, String.class))) {
			throw new io.jsonwebtoken.JwtException("토큰 용도가 올바르지 않습니다.");
		}
		return claims;
	}

	private static Date expiry(Duration ttl) {
		return new Date(System.currentTimeMillis() + ttl.toMillis());
	}
}
