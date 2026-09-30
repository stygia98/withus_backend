package com.withus;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
// 자체 쿠키 JWT 인증을 쓰므로 기본 사용자(user / 임시 비밀번호)는 만들지 않는다
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class WithusBackendApplication {

	public static void main(String[] args) {
		// JVM 타임존 고정 (PRD 2.4)
		TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
		SpringApplication.run(WithusBackendApplication.class, args);
	}

}
