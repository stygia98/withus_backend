package com.withus.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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
import com.withus.common.TestCsrf;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** AI-04 캠페인 기획 도우미 API (API_SPEC 11장). Mock LLM, 로컬 Docker DB */
@SpringBootTest(properties = "withus.ai.type=mock")
@AutoConfigureMockMvc
class AiCampaignPlanApiTest {

	private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	ObjectMapper mapper;

	RequestPostProcessor csrf;
	long memberId;

	@BeforeEach
	void setUp() throws Exception {
		csrf = TestCsrf.issue(mvc);
		memberId = jdbc.queryForObject("SELECT min(member_id) FROM member WHERE role = 'OWNER'", Long.class);
	}

	private RequestPostProcessor auth(Role role) {
		return authentication(new UsernamePasswordAuthenticationToken(new AuthMember(memberId, role), null,
			List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
	}

	private String planJson(String goal) throws Exception {
		return mvc.perform(post("/api/v1/ai/campaign-plans").with(auth(Role.MANAGER)).with(csrf)
				.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("goal", goal))))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
	}

	@Nested
	@Transactional
	class Rollback {

		@Test
		void 초안은_저장하지_않고_대상_수와_함께_돌려준다() throws Exception {
			long segmentsBefore = jdbc.queryForObject("SELECT count(*) FROM segment", Long.class);

			mvc.perform(post("/api/v1/ai/campaign-plans").with(auth(Role.MANAGER)).with(csrf)
					.contentType(MediaType.APPLICATION_JSON).content("{\"goal\":\"부산 고객에게 10% 쿠폰\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.plan.channel").value("EMAIL"))
				.andExpect(jsonPath("$.data.plan.segment.rule.groups[0].conditions[0].value").value("BUSAN"))
				.andExpect(jsonPath("$.data.plan.coupon.discountType").value("RATE"))
				.andExpect(jsonPath("$.data.plan.coupon.validFrom").value(TODAY.toString()))
				.andExpect(jsonPath("$.data.plan.coupon.validTo").value(TODAY.plusDays(6).toString()))
				.andExpect(jsonPath("$.data.target.total").isNumber());

			assertThat(jdbc.queryForObject("SELECT count(*) FROM segment", Long.class)).isEqualTo(segmentsBefore);
		}

		@Test
		void 저장하면_네_개가_만들어지고_캠페인은_DRAFT() throws Exception {
			JsonNode plan = mapper.readTree(planJson("부산 고객에게 5,000원 쿠폰")).get("data").get("plan");

			String res = mvc.perform(post("/api/v1/ai/campaign-plans/apply").with(auth(Role.OWNER)).with(csrf)
					.contentType(MediaType.APPLICATION_JSON).content(plan.toString()))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
			JsonNode ids = mapper.readTree(res).get("data");

			Map<String, Object> campaign = jdbc.queryForMap(
				"SELECT type, status, segment_id, template_id, coupon_id FROM campaign WHERE campaign_id = ?",
				ids.get("campaignId").asLong());
			assertThat(campaign).containsEntry("type", "ONE_TIME").containsEntry("status", "DRAFT")
				.containsEntry("segment_id", ids.get("segmentId").asLong())
				.containsEntry("template_id", ids.get("templateId").asLong())
				.containsEntry("coupon_id", ids.get("couponId").asLong());
			Map<String, Object> template = jdbc.queryForMap("SELECT channel, ad_yn, body FROM template WHERE template_id = ?",
				ids.get("templateId").asLong());
			assertThat(template).containsEntry("channel", "EMAIL").containsEntry("ad_yn", "Y");
			assertThat((String) template.get("body")).startsWith("<p>").endsWith("<a href=\"{{couponUrl}}\">쿠폰 받기</a></p>");
			assertThat(jdbc.queryForObject("SELECT discount_value FROM coupon WHERE coupon_id = ?", Integer.class,
				ids.get("couponId").asLong())).isEqualTo(5000);
		}

		@Test
		void 목표에_이메일이_있으면_AI로_보내지_않고_400() throws Exception {
			mvc.perform(post("/api/v1/ai/campaign-plans").with(auth(Role.MANAGER)).with(csrf)
					.contentType(MediaType.APPLICATION_JSON).content("{\"goal\":\"kim@example.com 고객에게 쿠폰\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("AI_PII_DETECTED"));
		}

		@Test
		void 예시를_새로_만들면_4개이고_보이던_것과_겹치지_않는다() throws Exception {
			String current = "[MOCK] 부산 고객에게 이번 주말 10% 할인 쿠폰";
			String res = mvc.perform(post("/api/v1/ai/campaign-plans/examples").with(auth(Role.MANAGER)).with(csrf)
					.contentType(MediaType.APPLICATION_JSON)
					.content(mapper.writeValueAsString(Map.of("current", List.of(current)))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.examples.length()").value(4))
				.andReturn().getResponse().getContentAsString();
			assertThat(mapper.readTree(res).get("data").get("examples").toString()).doesNotContain(current);

			mvc.perform(post("/api/v1/ai/campaign-plans/examples").with(auth(Role.STAFF)).with(csrf)
					.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isForbidden());
		}

		@Test
		void STAFF는_초안도_저장도_403() throws Exception {
			mvc.perform(post("/api/v1/ai/campaign-plans").with(auth(Role.STAFF)).with(csrf)
					.contentType(MediaType.APPLICATION_JSON).content("{\"goal\":\"부산 고객\"}"))
				.andExpect(status().isForbidden());
			// 본문 검증(@Valid)이 권한 검사보다 먼저라 형식이 맞는 초안을 보낸다
			String plan = mapper.readTree(planJson("부산 고객")).get("data").get("plan").toString();
			mvc.perform(post("/api/v1/ai/campaign-plans/apply").with(auth(Role.STAFF)).with(csrf)
					.contentType(MediaType.APPLICATION_JSON).content(plan))
				.andExpect(status().isForbidden());
		}
	}

	/** 트랜잭션 없이 실행해 실제 롤백을 확인한다 — 실패하면 앞에서 만든 타겟팅도 남지 않아야 한다 */
	@Test
	void 쿠폰_검증에_걸리면_타겟팅도_저장되지_않는다() throws Exception {
		String marker = "롤백 확인 " + UUID.randomUUID();
		JsonNode plan = mapper.readTree(planJson("부산 고객에게 5,000원 쿠폰")).get("data").get("plan");
		String broken = plan.toString()
			.replace(plan.get("segment").get("name").toString(), mapper.writeValueAsString(marker))
			.replace("\"validTo\":\"" + plan.get("coupon").get("validTo").stringValue() + "\"",
				"\"validTo\":\"" + TODAY.minusDays(1) + "\"");

		mvc.perform(post("/api/v1/ai/campaign-plans/apply").with(auth(Role.OWNER)).with(csrf)
				.contentType(MediaType.APPLICATION_JSON).content(broken))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("COUPON_INVALID_PERIOD"));

		assertThat(jdbc.queryForObject("SELECT count(*) FROM segment WHERE name = ?", Long.class, marker)).isZero();
	}
}
