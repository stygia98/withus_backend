package com.withus.auth.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** withus.jwt.* — secret 은 환경변수 JWT_SECRET (32자 이상) */
@ConfigurationProperties("withus.jwt")
public record JwtProperties(String secret, Duration accessTtl, Duration refreshTtl) {
}
