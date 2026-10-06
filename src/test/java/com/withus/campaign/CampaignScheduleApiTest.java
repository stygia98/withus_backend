package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
 * 캠페인 예상 소요 시간·예약·시작·예약 취소 (API_SPEC 6장, 캠페인 3/4). 20:50 컷오프는 SendWindowTest 에서
 * 순수 메서드로 이미 검증했으므로, 여기서는 API 경로(estimate·schedule·start) 의 연결만 확인한다.
 * 로컬 Docker DB, 테스트마다 롤백. 스케줄러는 끈다(withus.scheduler.send-dispatcher.enabled=false)
 */
@SpringBootTest(properties = "withus.scheduler.send-dispatcher.enabled=false")
@AutoConfigureMockMvc
@Transactional
class CampaignScheduleApiTest {

	private static final String PASSWORD = "correct-password";

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
	long memberId;
	long segmentId;
	/** 비광고 템플릿 — /start 는 서버의 실제 현재 시각을 쓰므로, 창 검사와 무관한 테스트는 이걸 써서 시간대 플레이키를 피한다 */
	long templateId;
	/** 광고 템플릿 — schedule·estimate 처럼 시각을 요청 파라미터로 고정해 결정적으로 창 검사를 테스트할 때만 쓴다 */
	long adTemplateId;

	@BeforeEach
	void setUp() throws Exception {
		xsrf = mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		Member member = new Member();
		member.setEmail("campaign-sch-" + UUID.randomUUID() + "@withus.local");
		member.setPassword(passwordEncoder.encode(PASSWORD));
		member.setName("캠페인");
		member.setRole(Role.MANAGER);
		memberMapper.insert(member);
		memberId = member.getMemberId();
		access = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
				.content("{\"email\":\"" + member.getEmail() + "\",\"password\":\"" + PASSWORD + "\"}"))
			.andReturn().getResponse().getCookie(AuthCookies.ACCESS_TOKEN);

		// segment·segment_rule 은 직접 INSERT 하지 않고 실제 API 로 만든다 — rule_json 검증·번역을
		// SegmentServiceImpl 이 하므로, dormant='N' 조건 하나로 새로 만든(기본값 dormant_yn='N') 고객을 모두 잡는다
		String rule = """
			{"operator":"AND","groups":[{"operator":"AND","conditions":[
			  {"field":"dormant","op":"EQ","value":"N"}]}]}
			""";
		String segmentResponse = write("/api/v1/segments", access,
			"{\"name\":\"세그먼트\",\"rule\":" + rule + "}")
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		segmentId = Long.parseLong(segmentResponse.replaceAll("(?s).*\"segmentId\":(\\d+).*", "$1"));

		templateId = jdbc.queryForObject("""
			INSERT INTO template (channel, name, subject, body, ad_yn, created_by)
			VALUES ('EMAIL', '템플릿', '제목', '<p>본문</p>', 'N', ?) RETURNING template_id
			""", Long.class, memberId);
		adTemplateId = jdbc.queryForObject("""
			INSERT INTO template (channel, name, subject, body, ad_yn, created_by)
			VALUES ('EMAIL', '광고 템플릿', '제목', '<p>본문</p>', 'Y', ?) RETURNING template_id
			""", Long.class, memberId);
	}

	/** 실행일 기준 서울의 내일 hh:mm — 고정 날짜는 하루만 지나도 과거 시각(400)이 되어 테스트가 깨진다 */
	private static String seoulTomorrow(int hour, int minute) {
		return LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1).atTime(hour, minute) + ":00+09:00";
	}

	private ResultActions write(String url, Cookie auth, String json) throws Exception {
		return mvc.perform(post(url).cookie(auth, xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
			.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private long customer() {
		return jdbc.queryForObject("""
			INSERT INTO customer (email, joined_at, source) VALUES (?, now(), 'MANUAL') RETURNING customer_id
			""", Long.class, "target-" + UUID.randomUUID() + "@withus.local");
	}

	private long oneTimeCampaign(long templateId, Long couponId) {
		return jdbc.queryForObject("""
			INSERT INTO campaign (name, type, status, segment_id, template_id, coupon_id, created_by)
			VALUES ('캠페인', 'ONE_TIME', 'DRAFT', ?, ?, ?, ?) RETURNING campaign_id
			""", Long.class, segmentId, templateId, couponId, memberId);
	}

	@Test
	void 저녁_9시_예약은_422와_nextAvailableAt을_돌려준다() throws Exception {
		long campaignId = oneTimeCampaign(adTemplateId, null);

		write("/api/v1/campaigns/" + campaignId + "/schedule", access,
			"""
			{"scheduledAt":"%s"}
			""".formatted(seoulTomorrow(21, 0)))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.error.code").value("CAMPAIGN_SEND_WINDOW_EXCEEDED"))
			.andExpect(jsonPath("$.error.details.nextAvailableAt").value(
				LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(2).atTime(8, 0) + ":00+09:00"));
	}

	@Test
	void estimate는_대기분을_포함해_20시50분_초과면_차단한다() throws Exception {
		customer(); // 대상 1건 (세그먼트 dormant='N' 조건에 걸림)
		long campaignId = oneTimeCampaign(adTemplateId, null);
		// ses.max-send-rate=1(건/초, application.yml) 이므로 대기분 65건 + 대상 1건 = 66초.
		// 20:49:00 시작이면 20:50:06 에 끝나 20:50 을 넘긴다
		long otherCampaignId = oneTimeCampaign(adTemplateId, null);
		jdbc.update("UPDATE campaign SET status = 'ACTIVE' WHERE campaign_id = ?", otherCampaignId);
		for (int i = 0; i < 65; i++) {
			jdbc.update("""
				INSERT INTO send_log (campaign_id, recipient, channel, status, kind, priority)
				VALUES (?, ?, 'EMAIL', 'PENDING', 'CAMPAIGN', 3)
				""", otherCampaignId, "backlog-" + i + "@withus.local");
		}

		mvc.perform(get("/api/v1/campaigns/" + campaignId + "/estimate")
				.param("startAt", seoulTomorrow(20, 49)).cookie(access))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.allowed").value(false))
			.andExpect(jsonPath("$.data.reason").value("SEND_WINDOW_EXCEEDED"))
			.andExpect(jsonPath("$.data.pendingBacklog").value(65));
	}

	@Test
	void 쿠폰_기간이_지났으면_COUPON_OUT_OF_PERIOD() throws Exception {
		long couponId = jdbc.queryForObject("""
			INSERT INTO coupon (name, discount_type, discount_value, valid_from, valid_to)
			VALUES ('만료 쿠폰', 'AMOUNT', 1000, ?, ?) RETURNING coupon_id
			""", Long.class, LocalDate.now().minusDays(30), LocalDate.now().minusDays(1));
		long campaignId = oneTimeCampaign(templateId, couponId);

		write("/api/v1/campaigns/" + campaignId + "/start", access, "{}")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.error.code").value("COUPON_OUT_OF_PERIOD"));
	}

	@Test
	void 예약_시작_취소_흐름() throws Exception {
		long campaignId = oneTimeCampaign(templateId, null);
		OffsetDateTime scheduledAt = OffsetDateTime.now(ZoneOffset.ofHours(9)).withHour(10).withMinute(0)
			.withSecond(0).withNano(0);
		if (scheduledAt.isBefore(OffsetDateTime.now(ZoneOffset.ofHours(9)))) {
			scheduledAt = scheduledAt.plusDays(1);
		}

		write("/api/v1/campaigns/" + campaignId + "/schedule", access,
			"{\"scheduledAt\":\"" + scheduledAt + "\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("SCHEDULED"));

		write("/api/v1/campaigns/" + campaignId + "/cancel-schedule", access, "{}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("DRAFT"))
			.andExpect(jsonPath("$.data.scheduledAt").doesNotExist());
	}

	@Test
	void 같은_캠페인을_두_번_시작해도_고객당_한_건만_적재된다() throws Exception {
		long customerId = customer();
		jdbc.update("UPDATE customer SET email_consent_yn = 'Y' WHERE customer_id = ?", customerId);
		long campaignId = oneTimeCampaign(templateId, null);

		write("/api/v1/campaigns/" + campaignId + "/start", access, "{}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("ACTIVE"));

		// 이미 ACTIVE 라 두 번째 시작은 CAMPAIGN_INVALID_STATUS 로 막힌다
		write("/api/v1/campaigns/" + campaignId + "/start", access, "{}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("CAMPAIGN_INVALID_STATUS"));

		Long count = jdbc.queryForObject(
			"SELECT count(*) FROM send_log WHERE campaign_id = ? AND customer_id = ?", Long.class, campaignId,
			customerId);
		assertThat(count).isEqualTo(1);
	}
}
