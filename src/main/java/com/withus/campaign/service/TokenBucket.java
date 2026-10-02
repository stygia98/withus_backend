package com.withus.campaign.service;

import java.util.function.LongSupplier;

/**
 * 외부 라이브러리 없이 구현한 토큰 버킷 속도 제한 (발송 큐 Plan 4장).
 * ses.max-send-rate(초당 허용 건수)만큼 매초 토큰을 리필한다.
 * 로컬 Mailpit 에는 자체 한도가 없지만 PRD 8.2 요구사항("로컬에서도 같은 속도 제한이 동작하는지 테스트")에 따라
 * 프로필과 무관하게 항상 적용한다.
 */
public class TokenBucket {

	private final double ratePerSecond;
	private final LongSupplier nanoTimeSupplier;
	private double tokens;
	private long lastRefillNanos;

	public TokenBucket(double ratePerSecond) {
		this(ratePerSecond, System::nanoTime);
	}

	/** 단위 테스트에서 가짜 시계를 주입해 리필 계산을 검증할 수 있게 열어둔 생성자 */
	public TokenBucket(double ratePerSecond, LongSupplier nanoTimeSupplier) {
		if (ratePerSecond <= 0) {
			throw new IllegalArgumentException("ratePerSecond 는 0보다 커야 합니다");
		}
		this.ratePerSecond = ratePerSecond;
		this.nanoTimeSupplier = nanoTimeSupplier;
		this.tokens = ratePerSecond; // 시작은 가득 찬 상태 (버스트 허용 한도 = 초당 허용 건수)
		this.lastRefillNanos = nanoTimeSupplier.getAsLong();
	}

	/** 토큰이 있으면 즉시 하나 소비하고, 없으면 다음 리필까지 블로킹 대기한다 */
	public synchronized void acquire() {
		while (true) {
			refill();
			if (tokens >= 1) {
				tokens -= 1;
				return;
			}
			sleepNanos((long) ((1 - tokens) / ratePerSecond * 1_000_000_000L));
		}
	}

	private void refill() {
		long now = nanoTimeSupplier.getAsLong();
		double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
		tokens = Math.min(ratePerSecond, tokens + elapsedSeconds * ratePerSecond);
		lastRefillNanos = now;
	}

	private void sleepNanos(long nanos) {
		try {
			Thread.sleep(Math.max(1, nanos / 1_000_000), (int) (nanos % 1_000_000));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("TokenBucket.acquire 대기 중 인터럽트됨", e);
		}
	}
}
