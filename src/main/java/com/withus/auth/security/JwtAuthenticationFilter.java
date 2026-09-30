package com.withus.auth.security;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.withus.auth.domain.AuthErrorCode;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * ACCESS_TOKEN 쿠키로 인증한다. 토큰이 잘못되면 인증하지 않고 사유만 남겨
 * SecurityErrorHandlers 가 401 AUTH_TOKEN_EXPIRED / AUTH_UNAUTHORIZED 로 응답하게 한다
 * (SecurityConfig 에서 직접 생성한다. @Component 로 두면 서블릿 필터로 한 번 더 등록된다)
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	static final String AUTH_ERROR_ATTRIBUTE = JwtAuthenticationFilter.class.getName() + ".error";

	private final JwtProvider jwtProvider;

	public JwtAuthenticationFilter(JwtProvider jwtProvider) {
		this.jwtProvider = jwtProvider;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws ServletException, IOException {
		String token = AuthCookies.read(request, AuthCookies.ACCESS_TOKEN);
		if (token != null) {
			try {
				AuthMember member = jwtProvider.parseAccessToken(token);
				var authentication = new UsernamePasswordAuthenticationToken(member, null,
					List.of(new SimpleGrantedAuthority("ROLE_" + member.role().name())));
				SecurityContextHolder.getContext().setAuthentication(authentication);
			} catch (ExpiredJwtException e) {
				request.setAttribute(AUTH_ERROR_ATTRIBUTE, AuthErrorCode.AUTH_TOKEN_EXPIRED);
			} catch (JwtException | IllegalArgumentException e) {
				request.setAttribute(AUTH_ERROR_ATTRIBUTE, AuthErrorCode.AUTH_UNAUTHORIZED);
			}
		}
		chain.doFilter(request, response);
	}
}
