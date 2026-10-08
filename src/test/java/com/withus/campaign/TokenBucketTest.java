package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.withus.campaign.service.TokenBucket;

/** 토큰 버킷 속도 제한 (발송 큐 Plan 4장) */
class TokenBucketTest {

	@Test
	void 시간이_충분히_지나면_대기_없이_리필된다() {
		AtomicLong fakeNanos = new AtomicLong(0);
		TokenBucket bucket = new TokenBucket(1, fakeNanos::get);

		bucket.acquire(); // 시작은 가득 찬 상태(토큰 1개) — 즉시 통과, 토큰 0

		fakeNanos.addAndGet(2_000_000_000L); // 가짜 시계로 2초를 흘려보낸다 — 실제로 기다리지 않는다
		long before = System.nanoTime();
		bucket.acquire(); // 리필돼 있으니 실제 대기 없이 즉시 통과해야 한다
		long elapsedMs = (System.nanoTime() - before) / 1_000_000;

		assertThat(elapsedMs).as("가짜 시계로 이미 리필됐으므로 실제로는 기다리지 않아야 한다").isLessThan(50);
	}

	@Test
	void rate가_1이면_5회_acquire가_약_4초_이상_걸린다() {
		TokenBucket bucket = new TokenBucket(1);

		long start = System.nanoTime();
		for (int i = 0; i < 5; i++) {
			bucket.acquire();
		}
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;

		// 첫 호출은 가득 찬 토큰으로 즉시 통과, 나머지 4회는 초당 1개 리필을 기다려야 한다
		assertThat(elapsedMs).isGreaterThanOrEqualTo(3_500);
	}
}
