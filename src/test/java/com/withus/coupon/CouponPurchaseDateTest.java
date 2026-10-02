package com.withus.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.withus.auth.domain.Role;
import com.withus.auth.security.AuthMember;

/**
 * 구매 등록(팀원1) → CouponService.markUsed(팀원3) 연결: 쿠폰 기간은 구매일 기준 (PRD F-10 ①, PL 리뷰 #16 재현 케이스).
 * 고객 사용하기(/c/[token])는 오늘 기준이라 같은 쿠폰이 거절된다. 로컬 Docker DB, 롤백
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
// with(csrf()) 는 공유 컨텍스트의 CSRF 저장소를 바꿔 끼워, 이후 실제 /auth/csrf 쿠키를 쓰는 테스트를 깨뜨린다.
// 실행 순서는 PC·OS 마다 다를 수 있으므로 끝나면 컨텍스트를 버린다 (docs/workflow-git.md 테스트 작성 규칙)
@DirtiesContext
class CouponPurchaseDateTest {

	private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;

	long memberId;
	long customerId;

	@BeforeEach
	void setUp() {
		memberId = jdbc.queryForObject("INSERT INTO member (email, password, name, role) VALUES (?, 'x', '관리자', 'MANAGER') "
			+ "RETURNING member_id", Long.class, "cpd-" + UUID.randomUUID() + "@withus.local");
		customerId = jdbc.queryForObject("""
			INSERT INTO customer (name, email, joined_at, source) VALUES ('구매일', ?, DATE '2031-01-01', 'MANUAL') RETURNING customer_id
			""", Long.class, "cpd-" + UUID.randomUUID() + "@example.com");
	}

	/** 이미 끝난 쿠폰(오늘 기준 40일 전 ~ 10일 전)을 이 고객에게 발급 */
	private Object[] expiredIssue() {
		long couponId = jdbc.queryForObject("""
			INSERT INTO coupon (name, discount_type, discount_value, valid_from, valid_to)
			VALUES ('지난달 쿠폰', 'AMOUNT', 5000, ?, ?) RETURNING coupon_id
			""", Long.class, TODAY.minusDays(40), TODAY.minusDays(10));
		return jdbc.queryForObject("""
			INSERT INTO coupon_issue (coupon_id, customer_id) VALUES (?, ?) RETURNING issue_id, CAST(token AS text)
			""", (rs, n) -> new Object[] { rs.getLong(1), rs.getString(2) }, couponId, customerId);
	}

	private ResultActions purchase(long issueId, LocalDate purchaseDate) throws Exception {
		return mvc.perform(post("/api/v1/customers/" + customerId + "/purchases")
			.with(authentication(new UsernamePasswordAuthenticationToken(new AuthMember(memberId, Role.MANAGER), null,
				List.of(new SimpleGrantedAuthority("ROLE_MANAGER")))))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"amount\":45000,\"couponIssueId\":%d,\"purchasedAt\":\"%sT14:10:00+09:00\"}"
				.formatted(issueId, purchaseDate)));
	}

	@Test
	void 지난_기간_쿠폰도_구매일이_기간_안이면_구매_등록과_사용_처리가_된다() throws Exception {
		long issueId = (long) expiredIssue()[0];

		purchase(issueId, TODAY.minusDays(20))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.couponIssueId").value(issueId));

		assertThat(jdbc.queryForObject("SELECT used_at IS NOT NULL FROM coupon_issue WHERE issue_id = ?", Boolean.class,
			issueId)).isTrue();
	}

	@Test
	void 구매일이_기간_밖이면_구매_등록이_거절된다() throws Exception {
		long issueId = (long) expiredIssue()[0];

		purchase(issueId, TODAY.minusDays(5))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.error.code").value("COUPON_NOT_USABLE"));
		assertThat(jdbc.queryForObject("SELECT used_at IS NULL FROM coupon_issue WHERE issue_id = ?", Boolean.class,
			issueId)).isTrue();
	}

	@Test
	void 고객_사용하기는_오늘_기준이라_지난_쿠폰을_거절한다() throws Exception {
		String token = (String) expiredIssue()[1];

		mvc.perform(post("/api/v1/public/coupons/" + token + "/use"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.error.code").value("COUPON_NOT_USABLE"));
	}
}
