package com.withus.auth.domain;

import java.time.OffsetDateTime;

/** member 테이블 (DB_SCHEMA 4장 1번) */
public class Member {

	private Long memberId;
	private String email;
	private String password;
	private String name;
	private Role role;
	private String activeYn;
	private String refreshTokenHash;
	private int failedLoginCount;
	private OffsetDateTime lockedUntil;

	public boolean isActive() {
		return "Y".equals(activeYn);
	}

	public boolean isLocked(OffsetDateTime now) {
		return lockedUntil != null && lockedUntil.isAfter(now);
	}

	public Long getMemberId() {
		return memberId;
	}

	public void setMemberId(Long memberId) {
		this.memberId = memberId;
	}

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = email;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public Role getRole() {
		return role;
	}

	public void setRole(Role role) {
		this.role = role;
	}

	public String getActiveYn() {
		return activeYn;
	}

	public void setActiveYn(String activeYn) {
		this.activeYn = activeYn;
	}

	public String getRefreshTokenHash() {
		return refreshTokenHash;
	}

	public void setRefreshTokenHash(String refreshTokenHash) {
		this.refreshTokenHash = refreshTokenHash;
	}

	public int getFailedLoginCount() {
		return failedLoginCount;
	}

	public void setFailedLoginCount(int failedLoginCount) {
		this.failedLoginCount = failedLoginCount;
	}

	public OffsetDateTime getLockedUntil() {
		return lockedUntil;
	}

	public void setLockedUntil(OffsetDateTime lockedUntil) {
		this.lockedUntil = lockedUntil;
	}
}
