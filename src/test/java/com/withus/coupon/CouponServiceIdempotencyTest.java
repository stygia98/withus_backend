package com.withus.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.withus.common.exception.BusinessException;
import com.withus.coupon.domain.CouponErrorCode;
import com.withus.coupon.service.CouponService;

/**
 * CouponService 계약: issue 는 같은 sendLogId 로 다시 호출하면 기존 토큰을 돌려준다 (발송 큐 재시도 대비, PRD 8.2).
 * 동시 호출은 스레드마다 다른 트랜잭션이어야 검증되므로 롤백 대신 커밋하고 끝나면 지운다 (로컬 Docker DB).
 */
@SpringBootTest
class CouponServiceIdempotencyTest {

	@Autowired
	CouponService service;
	@Autowired
	JdbcTemplate jdbc;

	long couponId;
	long customerId;
	final List<Long> sendLogIds = new ArrayList<>();

	@BeforeEach
	void setUp() {
		LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
		couponId = jdbc.queryForObject("""
			INSERT INTO coupon (name, discount_type, discount_value, valid_from, valid_to)
			VALUES ('멱등 테스트', 'AMOUNT', 1000, ?, ?) RETURNING coupon_id
			""", Long.class, today.minusDays(1), today.plusDays(1));
		customerId = jdbc.queryForObject("""
			INSERT INTO customer (name, email, joined_at, source) VALUES ('멱등', ?, DATE '2031-01-01', 'MANUAL')
			RETURNING customer_id
			""", Long.class, "idem-" + UUID.randomUUID() + "@example.com");
	}

	@AfterEach
	void tearDown() {
		jdbc.update("DELETE FROM coupon_issue WHERE coupon_id = ?", couponId);
		for (Long id : sendLogIds) {
			jdbc.update("DELETE FROM send_log WHERE send_log_id = ?", id);
		}
		jdbc.update("DELETE FROM customer WHERE customer_id = ?", customerId);
		jdbc.update("DELETE FROM coupon WHERE coupon_id = ?", couponId);
	}

	private long newSendLog() {
		long id = jdbc.queryForObject("""
			INSERT INTO send_log (customer_id, recipient, channel, kind, priority, status)
			VALUES (?, 'idem@withus.local', 'EMAIL', 'CAMPAIGN', 3, 'SENDING') RETURNING send_log_id
			""", Long.class, customerId);
		sendLogIds.add(id);
		return id;
	}

	@Test
	void 같은_sendLogId로_다시_호출하면_같은_토큰을_돌려준다() {
		long sendLogId = newSendLog();
		UUID first = service.issue(couponId, customerId, sendLogId);

		for (int i = 0; i < 3; i++) {
			assertThat(service.issue(couponId, customerId, sendLogId)).isEqualTo(first);
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM coupon_issue WHERE send_log_id = ?", Long.class, sendLogId))
			.isEqualTo(1L);
	}

	@Test
	void 같은_고객이라도_발송_건이_다르면_별도_발급이다() {
		// 워크플로우에서 같은 고객에게 SEND 노드가 여러 번 나가는 경우: 발송 1건당 발급 1건
		assertThat(service.issue(couponId, customerId, newSendLog()))
			.isNotEqualTo(service.issue(couponId, customerId, newSendLog()));
	}

	@Test
	void 동시에_같은_sendLogId로_호출해도_발급은_하나뿐이다() throws Exception {
		long sendLogId = newSendLog();
		int threads = 8;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		Set<UUID> tokens = ConcurrentHashMap.newKeySet();
		try {
			for (int i = 0; i < threads; i++) {
				pool.submit(() -> {
					start.await();
					tokens.add(service.issue(couponId, customerId, sendLogId));
					return null;
				});
			}
			start.countDown();
			pool.shutdown();
			assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
		} finally {
			pool.shutdownNow();
		}

		assertThat(tokens).hasSize(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM coupon_issue WHERE send_log_id = ?", Long.class, sendLogId))
			.isEqualTo(1L);
	}

	@Test
	void markUsed는_한_번만_성공하고_두_번째는_이미_사용() {
		long sendLogId = newSendLog();
		service.issue(couponId, customerId, sendLogId);
		long issueId = jdbc.queryForObject("SELECT issue_id FROM coupon_issue WHERE send_log_id = ?", Long.class, sendLogId);

		service.markUsed(issueId);

		assertThatThrownBy(() -> service.markUsed(issueId))
			.isInstanceOf(BusinessException.class)
			.extracting(e -> ((BusinessException) e).getErrorCode())
			.isEqualTo(CouponErrorCode.COUPON_ALREADY_USED);
	}

	@Test
	void markUsed는_기간이_지난_쿠폰을_거절한다() {
		long sendLogId = newSendLog();
		service.issue(couponId, customerId, sendLogId);
		long issueId = jdbc.queryForObject("SELECT issue_id FROM coupon_issue WHERE send_log_id = ?", Long.class, sendLogId);
		LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
		jdbc.update("UPDATE coupon SET valid_from = ?, valid_to = ? WHERE coupon_id = ?",
			today.minusDays(10), today.minusDays(1), couponId);

		assertThatThrownBy(() -> service.markUsed(issueId))
			.extracting(e -> ((BusinessException) e).getErrorCode())
			.isEqualTo(CouponErrorCode.COUPON_NOT_USABLE);
	}

	@Test
	void markUsed는_없는_발급이면_찾을_수_없음() {
		assertThatThrownBy(() -> service.markUsed(Long.MAX_VALUE))
			.extracting(e -> ((BusinessException) e).getErrorCode())
			.isEqualTo(CouponErrorCode.COUPON_NOT_FOUND);
	}
}
