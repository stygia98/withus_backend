package com.withus.auth.security;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

import com.withus.auth.domain.AuthErrorCode;
import com.withus.common.response.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * 필터 단계(컨트롤러 도달 전)의 인증·CSRF 실패를 공통 응답 형식으로 쓴다
 */
@Component
public class SecurityErrorHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

	private final ObjectMapper objectMapper;

	public SecurityErrorHandlers(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	/** 인증 없음·토큰 만료 → 401 */
	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
		AuthenticationException authException) throws IOException {
		Object reason = request.getAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE);
		write(response, reason instanceof AuthErrorCode code ? code : AuthErrorCode.AUTH_UNAUTHORIZED);
	}

	/** CSRF 실패 → 403 AUTH_CSRF_INVALID, 그 외 권한 부족 → 403 AUTH_FORBIDDEN */
	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
		AccessDeniedException accessDeniedException) throws IOException {
		write(response, accessDeniedException instanceof CsrfException
			? AuthErrorCode.AUTH_CSRF_INVALID
			: AuthErrorCode.AUTH_FORBIDDEN);
	}

	private void write(HttpServletResponse response, AuthErrorCode code) throws IOException {
		response.setStatus(code.status().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		objectMapper.writeValue(response.getOutputStream(), ApiResponse.fail(code));
	}
}
