package com.withus.auth.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.withus.auth.dto.LoginRequest;
import com.withus.auth.dto.MemberResponse;
import com.withus.auth.security.AuthCookies;
import com.withus.auth.security.AuthMember;
import com.withus.auth.security.JwtProvider;
import com.withus.auth.service.AuthService;
import com.withus.common.exception.BusinessException;
import com.withus.common.response.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

@Tag(name = "인증", description = "로그인·토큰 재발급·CSRF (API_SPEC 2장)")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

	private final AuthService authService;
	private final AuthCookies authCookies;
	private final JwtProvider jwtProvider;

	public AuthController(AuthService authService, AuthCookies authCookies, JwtProvider jwtProvider) {
		this.authService = authService;
		this.authCookies = authCookies;
		this.jwtProvider = jwtProvider;
	}

	@Operation(summary = "CSRF 쿠키 발급", description = "앱 시작 시 먼저 호출한다. XSRF-TOKEN 쿠키 값을 이후 상태 변경 요청의 X-XSRF-TOKEN 헤더로 보낸다.")
	@GetMapping("/csrf")
	public ApiResponse<Void> csrf(CsrfToken csrfToken) {
		// 토큰 값을 읽어야 지연 생성된 토큰이 쿠키로 저장된다
		csrfToken.getToken();
		return ApiResponse.ok(null);
	}

	@Operation(summary = "로그인", description = "성공 시 ACCESS_TOKEN(30분)·REFRESH_TOKEN(7일) httpOnly 쿠키 발급. 5회 연속 실패 시 5분 잠금.")
	@PostMapping("/login")
	public ApiResponse<MemberResponse> login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
		AuthService.Tokens tokens = authService.login(request.email(), request.password());
		writeCookies(response, tokens);
		return ApiResponse.ok(MemberResponse.from(tokens.member()));
	}

	@Operation(summary = "Access 토큰 재발급", description = "REFRESH_TOKEN 쿠키로 재발급하고 Refresh 도 교체한다. 실패 시 쿠키를 지운다.")
	@PostMapping("/refresh")
	public ApiResponse<MemberResponse> refresh(HttpServletRequest request, HttpServletResponse response) {
		try {
			AuthService.Tokens tokens = authService.refresh(AuthCookies.read(request, AuthCookies.REFRESH_TOKEN));
			writeCookies(response, tokens);
			return ApiResponse.ok(MemberResponse.from(tokens.member()));
		} catch (BusinessException e) {
			authCookies.clear(response);
			throw e;
		}
	}

	@Operation(summary = "로그아웃", description = "Refresh 토큰을 무효화하고 인증 쿠키를 만료시킨다.")
	@PostMapping("/logout")
	public ApiResponse<Void> logout(@AuthenticationPrincipal AuthMember me, HttpServletResponse response) {
		authService.logout(me.memberId());
		authCookies.clear(response);
		return ApiResponse.ok(null);
	}

	@Operation(summary = "내 정보")
	@GetMapping("/me")
	public ApiResponse<MemberResponse> me(@AuthenticationPrincipal AuthMember me) {
		return ApiResponse.ok(MemberResponse.from(authService.me(me.memberId())));
	}

	private void writeCookies(HttpServletResponse response, AuthService.Tokens tokens) {
		authCookies.write(response, tokens.accessToken(), jwtProvider.accessTtl(), tokens.refreshToken(),
			jwtProvider.refreshTtl());
	}
}
