package com.withus.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.withus.coupon.service.CouponService;
import com.withus.coupon.service.CouponServiceStub;

/**
 * CouponService.issue 의 멱등 계약: 같은 sendLogId 로 다시 호출하면 기존 토큰을 돌려준다 (발송 큐 재시도 대비, PRD 8.2).
 * 실구현으로 교체할 때도 같은 시나리오로 이 계약을 검증해야 한다.
 */
class CouponServiceIdempotencyTest {

	private final CouponService service = new CouponServiceStub();

	@Test
	void 같은_sendLogId로_다시_호출하면_같은_토큰을_돌려준다() {
		UUID first = service.issue(1L, 10L, 100L);
		UUID retry = service.issue(1L, 10L, 100L);

		assertThat(retry).isEqualTo(first);
	}

	@Test
	void 여러_번_재시도해도_토큰이_바뀌지_않는다() {
		UUID first = service.issue(1L, 10L, 100L);

		for (int i = 0; i < 5; i++) {
			assertThat(service.issue(1L, 10L, 100L)).isEqualTo(first);
		}
	}

	@Test
	void 다른_sendLogId면_서로_다른_토큰을_발급한다() {
		UUID a = service.issue(1L, 10L, 100L);
		UUID b = service.issue(1L, 10L, 101L);

		assertThat(a).isNotEqualTo(b);
	}

	@Test
	void 같은_고객이라도_발송_건이_다르면_별도_발급이다() {
		// 워크플로우에서 같은 고객에게 SEND 노드가 여러 번 나가는 경우: 발송 1건당 발급 1건
		assertThat(service.issue(1L, 10L, 200L)).isNotEqualTo(service.issue(1L, 10L, 201L));
	}

	@Test
	void 동시에_같은_sendLogId로_호출해도_토큰은_하나뿐이다() throws Exception {
		int threads = 16;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		Set<UUID> tokens = ConcurrentHashMap.newKeySet();
		try {
			for (int i = 0; i < threads; i++) {
				pool.submit(() -> {
					start.await();
					tokens.add(service.issue(1L, 10L, 300L));
					return null;
				});
			}
			start.countDown();
			pool.shutdown();
			assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		} finally {
			pool.shutdownNow();
		}

		assertThat(tokens).hasSize(1);
	}
}
