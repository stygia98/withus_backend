package com.withus.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
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
import tools.jackson.databind.ObjectMapper;

/**
 * 워크플로우 저장·조회·검증 API (API_SPEC 6장, 워크플로우 구조 3/3). 로컬 Docker DB, 테스트마다 롤백
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WorkflowApiTest {

	private static final String PASSWORD = "correct-password";

	@Autowired
	MockMvc mvc;
	@Autowired
	MemberMapper memberMapper;
	@Autowired
	PasswordEncoder passwordEncoder;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	ObjectMapper objectMapper;

	Cookie xsrf;
	Cookie access;
	long memberId;
	long segmentId;
	long campaignId;

	@BeforeEach
	void setUp() throws Exception {
		xsrf = mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		Member member = new Member();
		member.setEmail("workflow-" + UUID.randomUUID() + "@withus.local");
		member.setPassword(passwordEncoder.encode(PASSWORD));
		member.setName("워크플로우");
		member.setRole(Role.MANAGER);
		memberMapper.insert(member);
		memberId = member.getMemberId();
		access = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
				.content("{\"email\":\"" + member.getEmail() + "\",\"password\":\"" + PASSWORD + "\"}"))
			.andReturn().getResponse().getCookie(AuthCookies.ACCESS_TOKEN);

		segmentId = jdbc.queryForObject(
			"INSERT INTO segment (name, created_by) VALUES ('세그먼트', ?) RETURNING segment_id", Long.class, memberId);
		campaignId = jdbc.queryForObject("""
			INSERT INTO campaign (name, type, status, segment_id, trigger_type, created_by)
			VALUES ('여정', 'WORKFLOW', 'DRAFT', ?, 'CUSTOMER_REGISTERED', ?) RETURNING campaign_id
			""", Long.class, segmentId, memberId);
	}

	private ResultActions write(String method, String url, String json) throws Exception {
		var builder = "PUT".equals(method) ? put(url) : post(url);
		return mvc.perform(builder.cookie(access, xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
			.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private long template(boolean withCouponUrl) {
		String body = withCouponUrl ? "<p>쿠폰: {{couponUrl}}</p>" : "<p>본문</p>";
		return jdbc.queryForObject("""
			INSERT INTO template (channel, name, subject, body, ad_yn, created_by)
			VALUES ('EMAIL', '템플릿', '제목', ?, 'N', ?) RETURNING template_id
			""", Long.class, body, memberId);
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> getSteps() throws Exception {
		String json = mvc.perform(get("/api/v1/campaigns/" + campaignId + "/workflow").cookie(access))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		Map<String, Object> body = objectMapper.readValue(json, Map.class);
		Map<String, Object> data = (Map<String, Object>) body.get("data");
		return (List<Map<String, Object>>) data.get("steps");
	}

	@Test
	void PUT으로_저장한_구조가_GET에서_같은_모양으로_나온다() throws Exception {
		long templateId = template(false);
		String saveJson = """
			{"steps":[
			  {"key":"t","nodeType":"TRIGGER","config":{"triggerType":"CUSTOMER_REGISTERED"},"next":"s1"},
			  {"key":"s1","nodeType":"SEND_EMAIL","config":{"templateId":%d},"next":"e1"},
			  {"key":"e1","nodeType":"END","config":{}}
			]}
			""".formatted(templateId);

		write("PUT", "/api/v1/campaigns/" + campaignId + "/workflow", saveJson).andExpect(status().isOk());

		List<Map<String, Object>> steps = getSteps();
		assertThat(steps).hasSize(3);
		Map<String, Object> trigger = findByNodeType(steps, "TRIGGER");
		Map<String, Object> send = findByStepId(steps, trigger.get("next"));
		Map<String, Object> end = findByStepId(steps, send.get("next"));
		assertThat(send.get("nodeType")).isEqualTo("SEND_EMAIL");
		assertThat(end.get("nodeType")).isEqualTo("END");
		assertThat(end.get("next")).isNull();
	}

	@Test
	void ACTIVE_캠페인은_PUT이_거부된다() throws Exception {
		jdbc.update("UPDATE campaign SET status = 'ACTIVE' WHERE campaign_id = ?", campaignId);
		String saveJson = """
			{"steps":[{"key":"t","nodeType":"TRIGGER","config":{},"next":"e1"},
			           {"key":"e1","nodeType":"END","config":{}}]}
			""";

		write("PUT", "/api/v1/campaigns/" + campaignId + "/workflow", saveJson)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("CAMPAIGN_INVALID_STATUS"));
	}

	@Test
	void END으로_안_끝나면_400과_위반_목록을_돌려준다() throws Exception {
		String saveJson = """
			{"steps":[{"key":"t","nodeType":"TRIGGER","config":{},"next":"s1"},
			           {"key":"s1","nodeType":"SEND_SMS","config":{"templateId":1}}]}
			""";

		write("PUT", "/api/v1/campaigns/" + campaignId + "/workflow", saveJson)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("WORKFLOW_INVALID_STRUCTURE"))
			.andExpect(jsonPath("$.error.details").isArray());

		assertThat(getSteps()).isEmpty();
	}

	@Test
	void validate는_저장하지_않고_검사만_한다() throws Exception {
		String saveJson = """
			{"steps":[{"key":"t","nodeType":"TRIGGER","config":{},"next":"s1"},
			           {"key":"s1","nodeType":"SEND_SMS","config":{"templateId":1}}]}
			""";

		write("POST", "/api/v1/campaigns/" + campaignId + "/workflow/validate", saveJson)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.valid").value(false));

		assertThat(getSteps()).isEmpty();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> findByNodeType(List<Map<String, Object>> steps, String nodeType) {
		return steps.stream().filter(s -> nodeType.equals(s.get("nodeType"))).findFirst()
			.orElseThrow(() -> new AssertionError(nodeType + " 노드 없음"));
	}

	private Map<String, Object> findByStepId(List<Map<String, Object>> steps, Object stepId) {
		return steps.stream().filter(s -> String.valueOf(s.get("stepId")).equals(String.valueOf(stepId))).findFirst()
			.orElseThrow(() -> new AssertionError("stepId " + stepId + " 없음"));
	}
}
