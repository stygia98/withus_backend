package com.withus.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.withus.auth.domain.Member;
import com.withus.auth.domain.Role;
import com.withus.auth.mapper.MemberMapper;
import com.withus.auth.security.AuthCookies;

import jakarta.servlet.http.Cookie;

/**
 * 쿠폰 관리 API(API_SPEC 7장)와 고객 쿠폰 공개 API(8장). 로컬 Docker DB, 테스트마다 롤백
 * PRD 10.3: "메일로 받은 쿠폰을 /c/[token]에서 확인하고, 사용 처리하면 전환율에 반영된다" 의 백엔드 부분
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CouponApiTest {

	private static final String PASSWORD = "correct-password";
	private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

	@Autowired
	MockMvc mvc;
	@Autowired
	MemberMapper memberMapper;
	@Autowired
	PasswordEncoder passwordEncoder;
	@Autowired
	JdbcTemplate jdbc;

	Cookie xsrf;
	Cookie access;

	@BeforeEach
	void setUp() throws Exception {
		xsrf = mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		access = login(Role.MANAGER);
	}

	private Cookie login(Role role) throws Exception {
		Member member = new Member();
		member.setEmail("coupon-" + UUID.randomUUID() + "@withus.local");
		member.setPassword(passwordEncoder.encode(PASSWORD));
		member.setName("쿠폰");
		member.setRole(role);
		memberMapper.insert(member);
		return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
				.content("{\"email\":\"" + member.getEmail() + "\",\"password\":\"" + PASSWORD + "\"}"))
			.andReturn().getResponse().getCookie(AuthCookies.ACCESS_TOKEN);
	}

	private ResultActions write(String method, String url, Cookie auth, String json) throws Exception {
		var builder = "PUT".equals(method) ? put(url) : post(url);
		return mvc.perform(builder.cookie(auth, xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
			.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private static String body(String type, int value, Integer cap, LocalDate from, LocalDate to) {
		return """
			{"name":"가을 감사 쿠폰","discountType":"%s","discountValue":%d,"maxDiscountAmount":%s,"validFrom":"%s","validTo":"%s"}
			""".formatted(type, value, cap, from, to);
	}

	private long createCoupon(String json) throws Exception {
		String response = write("POST", "/api/v1/coupons", access, json)
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return Long.parseLong(response.replaceAll("(?s).*\"couponId\":(\\d+).*", "$1"));
	}

	private long customer(String name) {
		return jdbc.queryForObject("""
			INSERT INTO customer (name, email, joined_at, source) VALUES (?, ?, DATE '2031-01-01', 'MANUAL') RETURNING customer_id
			""", Long.class, name, "cpn-" + UUID.randomUUID() + "@example.com");
	}

	/** 발송 큐가 발송 직전에 CouponService.issue 를 부른 것과 같은 상태 */
	private String issue(long couponId, long customerId) {
		long sendLogId = jdbc.queryForObject("""
			INSERT INTO send_log (customer_id, recipient, channel, kind, priority, status)
			VALUES (?, 'cpn@withus.local', 'EMAIL', 'CAMPAIGN', 3, 'SENT') RETURNING send_log_id
			""", Long.class, customerId);
		return jdbc.queryForObject("""
			INSERT INTO coupon_issue (coupon_id, customer_id, send_log_id) VALUES (?, ?, ?) RETURNING CAST(token AS text)
			""", String.class, couponId, customerId, sendLogId);
	}

	// ---- 관리 API ----

	@Test
	void 정률_쿠폰은_상한이_없으면_400() throws Exception {
		write("POST", "/api/v1/coupons", access, body("RATE", 15, null, TODAY, TODAY.plusDays(30)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("COUPON_RATE_CAP_REQUIRED"));
	}

	@Test
	void 종료일이_시작일보다_빠르면_400() throws Exception {
		write("POST", "/api/v1/coupons", access, body("AMOUNT", 5000, null, TODAY, TODAY.minusDays(1)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("COUPON_INVALID_PERIOD"));
	}

	@Test
	void 정률은_100퍼센트를_넘을_수_없다() throws Exception {
		write("POST", "/api/v1/coupons", access, body("RATE", 101, 1000, TODAY, TODAY))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
	}

	@Test
	void 정액_쿠폰의_상한은_저장하지_않는다() throws Exception {
		long couponId = createCoupon(body("AMOUNT", 5000, 9999, TODAY, TODAY.plusDays(30)));

		mvc.perform(get("/api/v1/coupons/" + couponId).cookie(access))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.maxDiscountAmount").value(nullValue()))
			.andExpect(jsonPath("$.data.validFrom").value(TODAY.toString()))
			.andExpect(jsonPath("$.data.issuedCount").value(0));
	}

	@Test
	void 발급_전에는_모든_값을_수정할_수_있다() throws Exception {
		long couponId = createCoupon(body("AMOUNT", 5000, null, TODAY, TODAY.plusDays(30)));

		write("PUT", "/api/v1/coupons/" + couponId, access, body("RATE", 10, 20000, TODAY.plusDays(1), TODAY.plusDays(5)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.discountType").value("RATE"))
			.andExpect(jsonPath("$.data.maxDiscountAmount").value(20000));
	}

	@Test
	void 발급_후에는_종료일_연장만_허용한다() throws Exception {
		long couponId = createCoupon(body("AMOUNT", 5000, null, TODAY, TODAY.plusDays(30)));
		issue(couponId, customer("발급"));

		write("PUT", "/api/v1/coupons/" + couponId, access, body("AMOUNT", 7000, null, TODAY, TODAY.plusDays(30)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("COUPON_ALREADY_ISSUED"));
		write("PUT", "/api/v1/coupons/" + couponId, access, body("AMOUNT", 5000, null, TODAY, TODAY.plusDays(10)))
			.andExpect(status().isConflict());

		write("PUT", "/api/v1/coupons/" + couponId, access, body("AMOUNT", 5000, null, TODAY, TODAY.plusDays(60)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.validTo").value(TODAY.plusDays(60).toString()))
			.andExpect(jsonPath("$.data.issuedCount").value(1));
	}

	@Test
	void 없는_쿠폰은_404() throws Exception {
		mvc.perform(get("/api/v1/coupons/" + Long.MAX_VALUE).cookie(access))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("COUPON_NOT_FOUND"));
	}

	@Test
	void STAFF는_조회만_가능하다() throws Exception {
		Cookie staff = login(Role.STAFF);

		mvc.perform(get("/api/v1/coupons").cookie(staff)).andExpect(status().isOk());
		write("POST", "/api/v1/coupons", staff, body("AMOUNT", 5000, null, TODAY, TODAY))
			.andExpect(status().isForbidden());
	}

	@Test
	void 발급_목록과_고객별_사용_가능_쿠폰() throws Exception {
		long couponId = createCoupon(body("AMOUNT", 5000, null, TODAY, TODAY.plusDays(30)));
		long expiredId = createCoupon(body("AMOUNT", 3000, null, TODAY.minusDays(10), TODAY.minusDays(1)));
		long customerId = customer("김민지");
		issue(couponId, customerId);
		issue(expiredId, customerId);

		mvc.perform(get("/api/v1/coupons/" + couponId + "/issues").cookie(access))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalElements").value(1))
			.andExpect(jsonPath("$.data.content[0].customerName").value("김민지"))
			.andExpect(jsonPath("$.data.content[0].status").value("USABLE"))
			.andExpect(jsonPath("$.data.content[0].token").doesNotExist());

		mvc.perform(get("/api/v1/customers/" + customerId + "/coupon-issues").cookie(access))
			.andExpect(jsonPath("$.data.length()").value(2));
		mvc.perform(get("/api/v1/customers/" + customerId + "/coupon-issues").param("usable", "true").cookie(access))
			.andExpect(jsonPath("$.data.length()").value(1))
			.andExpect(jsonPath("$.data[0].couponId").value(couponId));
	}

	// ---- 고객 공개 API (인증·CSRF 없음) ----

	@Test
	void 고객_카드는_이름을_마스킹하고_조회로는_상태가_바뀌지_않는다() throws Exception {
		long couponId = createCoupon(body("AMOUNT", 5000, null, TODAY, TODAY.plusDays(30)));
		String token = issue(couponId, customer("김민지"));

		for (int i = 0; i < 2; i++) {
			mvc.perform(get("/api/v1/public/coupons/" + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.customerName").value("김**"))
				.andExpect(jsonPath("$.data.couponName").value("가을 감사 쿠폰"))
				.andExpect(jsonPath("$.data.status").value("USABLE"));
		}
		assertThat(jdbc.queryForObject("SELECT used_at IS NULL FROM coupon_issue WHERE token = CAST(? AS uuid)",
			Boolean.class, token)).isTrue();
	}

	@Test
	void 사용하기는_POST로_한_번만_되고_전환으로_기록된다() throws Exception {
		long couponId = createCoupon(body("RATE", 15, 30000, TODAY, TODAY.plusDays(30)));
		String token = issue(couponId, customer("이서준"));

		mvc.perform(post("/api/v1/public/coupons/" + token + "/use"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("USED"));
		mvc.perform(post("/api/v1/public/coupons/" + token + "/use"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("COUPON_ALREADY_USED"));

		mvc.perform(get("/api/v1/coupons/" + couponId).cookie(access))
			.andExpect(jsonPath("$.data.usedCount").value(1));
		// purchase 는 만들지 않는다 (금액이 없는 경로)
		assertThat(jdbc.queryForObject("""
			SELECT count(*) FROM purchase p JOIN coupon_issue i ON i.issue_id = p.coupon_issue_id
			WHERE i.token = CAST(? AS uuid)
			""", Long.class, token)).isZero();
	}

	@Test
	void 기간_밖이면_사용할_수_없다() throws Exception {
		long notStarted = createCoupon(body("AMOUNT", 5000, null, TODAY.plusDays(1), TODAY.plusDays(30)));
		String token = issue(notStarted, customer("박"));

		mvc.perform(get("/api/v1/public/coupons/" + token))
			.andExpect(jsonPath("$.data.status").value("NOT_STARTED"))
			.andExpect(jsonPath("$.data.customerName").value("박"));
		mvc.perform(post("/api/v1/public/coupons/" + token + "/use"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.error.code").value("COUPON_NOT_USABLE"));
	}

	@Test
	void 없는_토큰이나_형식이_틀린_토큰은_404() throws Exception {
		mvc.perform(get("/api/v1/public/coupons/" + UUID.randomUUID()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("COUPON_NOT_FOUND"));
		mvc.perform(post("/api/v1/public/coupons/not-a-uuid/use"))
			.andExpect(status().isNotFound());
	}
}
