package com.withus.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.withus.auth.domain.Member;
import com.withus.auth.domain.Role;
import com.withus.auth.mapper.MemberMapper;

/**
 * 최초 OWNER 계정 1개를 환경변수(OWNER_EMAIL, OWNER_PASSWORD)로 만든다 (PRD 3장)
 * OWNER 가 이미 있거나 환경변수가 없으면 아무것도 하지 않는다
 */
@Component
public class OwnerInitializer implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(OwnerInitializer.class);

	private final MemberMapper memberMapper;
	private final PasswordEncoder passwordEncoder;
	private final String ownerEmail;
	private final String ownerPassword;

	public OwnerInitializer(MemberMapper memberMapper, PasswordEncoder passwordEncoder,
		@Value("${withus.owner.email:}") String ownerEmail, @Value("${withus.owner.password:}") String ownerPassword) {
		this.memberMapper = memberMapper;
		this.passwordEncoder = passwordEncoder;
		this.ownerEmail = ownerEmail;
		this.ownerPassword = ownerPassword;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (memberMapper.existsOwner()) {
			return;
		}
		if (ownerEmail.isBlank() || ownerPassword.isBlank()) {
			log.warn("OWNER 계정이 없습니다. OWNER_EMAIL, OWNER_PASSWORD 환경변수를 설정하고 다시 시작하세요.");
			return;
		}
		Member owner = new Member();
		owner.setEmail(AuthService.normalizeEmail(ownerEmail));
		owner.setPassword(passwordEncoder.encode(ownerPassword));
		owner.setName("관리자");
		owner.setRole(Role.OWNER);
		memberMapper.insert(owner);
		log.info("최초 OWNER 계정을 만들었습니다: {}", owner.getEmail());
	}
}
