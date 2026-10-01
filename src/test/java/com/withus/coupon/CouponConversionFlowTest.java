package com.withus.coupon;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.withus.auth.domain.Role;
import com.withus.auth.security.AuthMember;
import com.withus.coupon.service.CouponService;

/**
 * PRD 10.3 시연 시나리오를 DB 직접 조작 없이 실제 경로로만 잇는다:
 * 발송 직전 발급(CouponService.issue) → 고객 페이지 조회(GET, 변화 없음) → 사용하기(POST) 또는 관리자 구매 등록 → 캠페인 전환율.
 * - "메일로 받은 쿠폰을 /c/[token]에서 확인하고, 사용 처리하면 전환율에 반영된다"
 * - "관리자가 구매를 등록하면 … 전환율에 반영된다"
 * - "고객 페이지의 '사용하기'는 한 번만 되고, 링크를 열기만 해서는 사용 처리되지 않는다"
 * 로컬 Docker DB, 롤백
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CouponConversionFlowTest {

	private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	CouponService couponService;

	long memberId;
	long campaignId;
	long couponId;

	@BeforeEach
	void setUp() {
		memberId = jdbc.queryForObject("INSERT INTO member (email, password, name, role) VALUES (?, 'x', '시연', 'MANAGER') "
			+ "RETURNING member_id", Long.class, "flow-" + UUID.randomUUID() + "@withus.local");
		long segmentId = jdbc.queryForObject("INSERT INTO segment (name, created_by) VALUES ('flow', ?) RETURNING segment_id",
			Long.class, memberId);
		couponId = jdbc.queryForObject("""
			INSERT INTO coupon (name, discount_type, discount_value, valid_from, valid_to)
			VALUES ('가을 감사 쿠폰', 'AMOUNT', 5000, ?, ?) RETURNING coupon_id
			""", Long.class, TODAY.minusDays(1), TODAY.plusDays(30));
		campaignId = jdbc.queryForObject("""
			INSERT INTO campaign (name, type, status, segment_id, coupon_id, created_by)
			VALUES ('가을 감사 쿠폰 발송', 'ONE_TIME', 'ACTIVE', ?, ?, ?) RETURNING campaign_id
			""", Long.class, segmentId, couponId, memberId);
	}

	private RequestPostProcessor manager() {
		return authentication(new UsernamePasswordAuthenticationToken(new AuthMember(memberId, Role.MANAGER), null,
			List.of(new SimpleGrantedAuthority("ROLE_MANAGER"))));
	}

	private record Received(long customerId, long sendLogId) {
	}

	/** 발송 큐가 렌더링 직전에 쿠폰을 발급하고 성공한 것과 같은 상태. 쿠폰 토큰을 돌려준다 */
	private Received sentWithCoupon() {
		long customerId = jdbc.queryForObject("""
			INSERT INTO customer (name, email, joined_at, source) VALUES ('김민지', ?, DATE '2031-01-01', 'MANUAL') RETURNING customer_id
			""", Long.class, "flow-" + UUID.randomUUID() + "@example.com");
		long sendLogId = jdbc.queryForObject("""
			INSERT INTO send_log (campaign_id, customer_id, recipient, channel, kind, priority, status)
			VALUES (?, ?, 'flow@withus.local', 'EMAIL', 'CAMPAIGN', 3, 'SENDING') RETURNING send_log_id
			""", Long.class, campaignId, customerId);
		couponService.issue(couponId, customerId, sendLogId);
		jdbc.update("UPDATE send_log SET status = 'SENT', sent_at = now() WHERE send_log_id = ?", sendLogId);
		return new Received(customerId, sendLogId);
	}

	private String tokenOf(Received r) {
		return couponService.issue(couponId, r.customerId(), r.sendLogId()).toString(); // 멱등: 같은 토큰
	}

	@Test
	void 고객_사용하기와_관리자_구매_등록이_캠페인_전환율에_반영된다() throws Exception {
		Received byCustomer = sentWithCoupon();
		Received byPurchase = sentWithCoupon();
		sentWithCoupon(); // 쓰지 않은 고객

		// 링크를 열기만 해서는(GET, 메일 스캐너 포함) 전환이 아니다
		String token = tokenOf(byCustomer);
		mvc.perform(get("/api/v1/public/coupons/" + token)).andExpect(jsonPath("$.data.status").value("USABLE"));
		mvc.perform(get("/api/v1/analytics/campaigns/" + campaignId).with(manager()))
			.andExpect(jsonPath("$.data.kpi.sent").value(3))
			.andExpect(jsonPath("$.data.kpi.couponUsed").value(0));

		// ① 고객 페이지 '사용하기'(POST) — 한 번만 된다
		mvc.perform(post("/api/v1/public/coupons/" + token + "/use")).andExpect(status().isOk());
		mvc.perform(post("/api/v1/public/coupons/" + token + "/use")).andExpect(status().isConflict());

		// ② 관리자 구매 등록에서 쿠폰 선택 (팀원1 → CouponService.markUsed)
		long issueId = jdbc.queryForObject("SELECT issue_id FROM coupon_issue WHERE send_log_id = ?", Long.class,
			byPurchase.sendLogId());
		mvc.perform(post("/api/v1/customers/" + byPurchase.customerId() + "/purchases").with(manager()).with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":45000,\"couponIssueId\":%d}".formatted(issueId)))
			.andExpect(status().isOk());

		// 전환율 = 쿠폰 사용 고유 고객 / 발송 성공 = 2 / 3
		mvc.perform(get("/api/v1/analytics/campaigns/" + campaignId).with(manager()))
			.andExpect(jsonPath("$.data.kpi.couponUsed").value(2))
			.andExpect(jsonPath("$.data.kpi.conversionRate").value(0.6667))
			.andExpect(jsonPath("$.data.funnel[4].count").value(2));
		mvc.perform(get("/api/v1/coupons/" + couponId).with(manager()))
			.andExpect(jsonPath("$.data.issuedCount").value(3))
			.andExpect(jsonPath("$.data.usedCount").value(2));
	}
}
