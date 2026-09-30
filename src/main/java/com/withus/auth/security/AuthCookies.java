package com.withus.auth.security;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 인증 쿠키 발급·만료 (API_SPEC 1.4). 모두 httpOnly·SameSite=Lax, Secure 는 withus.cookie.secure
 */
@Component
public class AuthCookies {

	public static final String ACCESS_TOKEN = "ACCESS_TOKEN";
	public static final String REFRESH_TOKEN = "REFRESH_TOKEN";
	private static final String REFRESH_PATH = "/api/v1/auth";

	private final boolean secure;

	public AuthCookies(@Value("${withus.cookie.secure:true}") boolean secure) {
		this.secure = secure;
	}

	public void write(HttpServletResponse response, String accessToken, Duration accessTtl, String refreshToken,
		Duration refreshTtl) {
		add(response, ACCESS_TOKEN, accessToken, "/", accessTtl);
		add(response, REFRESH_TOKEN, refreshToken, REFRESH_PATH, refreshTtl);
	}

	public void clear(HttpServletResponse response) {
		add(response, ACCESS_TOKEN, "", "/", Duration.ZERO);
		add(response, REFRESH_TOKEN, "", REFRESH_PATH, Duration.ZERO);
	}

	public static String read(HttpServletRequest request, String name) {
		if (request.getCookies() == null) {
			return null;
		}
		for (Cookie cookie : request.getCookies()) {
			if (name.equals(cookie.getName()) && !cookie.getValue().isEmpty()) {
				return cookie.getValue();
			}
		}
		return null;
	}

	private void add(HttpServletResponse response, String name, String value, String path, Duration maxAge) {
		ResponseCookie cookie = ResponseCookie.from(name, value)
			.httpOnly(true)
			.secure(secure)
			.sameSite("Lax")
			.path(path)
			.maxAge(maxAge)
			.build();
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
	}
}
