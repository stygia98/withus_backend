package com.withus.auth.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * 쿠키 JWT 인증 + CSRF (PRD 9장, API_SPEC 1.1·1.4)
 * - 공개 경로 외 모든 API 인증 필수
 * - 상태 변경 요청은 X-XSRF-TOKEN 헤더 필수. 공개 경로는 자체 토큰으로 검증하므로 CSRF 제외
 * - 역할 검사는 컨트롤러에서 @PreAuthorize("hasAnyRole('OWNER','MANAGER')") 로 한다
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

	/** 인증 없이 접근하는 공개 경로 (자체 토큰·서명으로 검증) — CSRF 도 제외 */
	private static final String[] PUBLIC_PATHS = {
		"/t/**",
		"/api/v1/public/**",
		"/api/v1/unsubscribe/one-click/**",
		"/api/webhooks/**",
	};

	/**
	 * PL 리뷰 대상(2026-10-01, 팀원2): 메일 본문 이미지는 수신자가 로그인 없이 봐야 해서 GET 만 공개한다.
	 * 업로드(POST /api/v1/files/images)는 이 배열에 넣지 않아 인증·CSRF 를 그대로 받는다.
	 * PRD 8.5: "S3 버킷은 이미지 경로만 공개 읽기 허용"과 같은 맥락 (local 은 /files/**, prod 는 S3 버킷 자체가 공개)
	 */
	private static final String PUBLIC_GET_FILES = "/files/**";

	/** 인증 없이 접근하지만 CSRF 검사는 받는 경로 (PRD 9장: 로그인·재발급도 CSRF 대상) */
	private static final String[] AUTH_ENTRY_PATHS = {
		"/api/v1/auth/csrf",
		"/api/v1/auth/login",
		"/api/v1/auth/refresh",
	};

	private static final String[] API_DOC_PATHS = {
		"/swagger-ui.html",
		"/swagger-ui/**",
		"/v3/api-docs/**",
	};

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, JwtProvider jwtProvider,
		SecurityErrorHandlers errorHandlers) throws Exception {
		http
			.csrf(csrf -> csrf
				// SPA 설정: JS 가 읽을 수 있는 XSRF-TOKEN 쿠키, 원문 토큰을 헤더로 받는다
				.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
				.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
				// 쿠키 JWT(STATELESS)에서는 매 요청이 '새 로그인'으로 간주돼 기본 전략이 XSRF 쿠키를 지운다.
				// 그러면 GET 다음의 POST 가 403 이 되므로 요청마다 토큰을 교체하지 않는다
				.sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy())
				.ignoringRequestMatchers(PUBLIC_PATHS))
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.httpBasic(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.authorizeHttpRequests(auth -> auth
				.requestMatchers(PUBLIC_PATHS).permitAll()
				.requestMatchers(HttpMethod.GET, PUBLIC_GET_FILES).permitAll()
				.requestMatchers(AUTH_ENTRY_PATHS).permitAll()
				.requestMatchers(API_DOC_PATHS).permitAll()
				.requestMatchers("/error").permitAll()
				.anyRequest().authenticated())
			.exceptionHandling(ex -> ex
				.authenticationEntryPoint(errorHandlers)
				.accessDeniedHandler(errorHandlers))
			.addFilterBefore(new JwtAuthenticationFilter(jwtProvider), UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}
}
