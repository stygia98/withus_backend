package com.withus.tracking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import com.withus.auth.domain.Member;
import com.withus.auth.domain.Role;
import com.withus.auth.mapper.MemberMapper;
import com.withus.tracking.config.TrackingProperties;
import com.withus.tracking.service.TrackingLinkService;

/**
 * TrackingLinkService.rewrite 실구현 (PRD 8.1): 템플릿 고정 링크 자동 등록, 일회성·워크플로우 템플릿 찾기,
 * 템플릿을 모르는 발송은 픽셀만. 로컬 Docker DB 를 쓰고 테스트마다 롤백한다
 */
@SpringBootTest
@Transactional
class TrackingLinkServiceTest {

	@Autowired
	TrackingLinkService service;
	@Autowired
	TrackingProperties properties;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	MemberMapper memberMapper;
	@Autowired
	PasswordEncoder passwordEncoder;

	long memberId;
	long segmentId;
	String base;

	static final String TEMPLATE_BODY = "<p>{{name|고객}}님</p>"
		+ "<a href=\"https://shop.example.com/sale\">세일</a>"
		+ "<a href=\"https://shop.example.com/new?a=1&amp;b=2\">신상</a>"
		+ "<a href=\"https://shop.example.com/sale\">세일 다시</a>"
		+ "<a href=\"{{couponUrl}}\">쿠폰</a>"
		+ "<a href=\"https://shop.example.com/me?n={{name}}\">개인 링크</a>"
		+ "<a href=\"mailto:cs@example.com\">문의</a>";

	@BeforeEach
	void setUp() {
		Member member = new Member();
		member.setEmail("link-" + UUID.randomUUID() + "@withus.local");
		member.setPassword(passwordEncoder.encode("pw-for-test"));
		member.setName("링크테스트");
		member.setRole(Role.MANAGER);
		memberMapper.insert(member);
		memberId = member.getMemberId();
		segmentId = jdbc.queryForObject("INSERT INTO segment (name, created_by) VALUES ('link-test', ?) RETURNING segment_id",
			Long.class, memberId);
		String configured = properties.baseUrl();
		base = configured.endsWith("/") ? configured.substring(0, configured.length() - 1) : configured;
	}

	@Test
	void 일회성_캠페인은_템플릿_링크를_등록하고_고정_링크만_추적_URL로_바꾼다() {
		long templateId = insertTemplate(TEMPLATE_BODY);
		long sendLogId = insertSendLog(insertOneTimeCampaign(templateId), null);
		String token = tokenOf(sendLogId);

		String result = service.rewrite(rendered("https://app.test/c/9f0e"), sendLogId);

		Map<String, Long> links = linksOf(templateId);
		assertThat(links).containsOnlyKeys("https://shop.example.com/sale", "https://shop.example.com/new?a=1&b=2");
		assertThat(result)
			.contains("<a href=\"" + base + "/t/c/" + token + "/" + links.get("https://shop.example.com/sale") + "\">세일</a>")
			.contains("<a href=\"" + base + "/t/c/" + token + "/" + links.get("https://shop.example.com/sale") + "\">세일 다시</a>")
			.contains("<a href=\"" + base + "/t/c/" + token + "/" + links.get("https://shop.example.com/new?a=1&b=2") + "\">신상</a>")
			// 개인 치환자가 들어간 링크·쿠폰·mailto 는 그대로
			.contains("<a href=\"https://shop.example.com/me?n=홍길동\">개인 링크</a>")
			.contains("<a href=\"mailto:cs@example.com\">문의</a>")
			.contains("/t/o/" + token + ".gif");
	}

	@Test
	void 링크_순서는_템플릿_등장_순서다() {
		long templateId = insertTemplate(TEMPLATE_BODY);
		service.rewrite(rendered("https://app.test/c/1"), insertSendLog(insertOneTimeCampaign(templateId), null));

		List<Map<String, Object>> rows = jdbc.queryForList(
			"SELECT original_url, link_order FROM track_link WHERE template_id = ? ORDER BY link_order", templateId);

		assertThat(rows).extracting(r -> r.get("original_url"))
			.containsExactly("https://shop.example.com/sale", "https://shop.example.com/new?a=1&b=2");
		assertThat(rows).extracting(r -> r.get("link_order")).containsExactly(1, 2);
	}

	@Test
	void 같은_템플릿으로_여러_고객에게_보내도_링크_행은_늘지_않는다() {
		long templateId = insertTemplate(TEMPLATE_BODY);
		long campaignId = insertOneTimeCampaign(templateId);

		service.rewrite(rendered("https://app.test/c/1"), insertSendLog(campaignId, null));
		service.rewrite(rendered("https://app.test/c/2"), insertSendLog(campaignId, null));
		service.rewrite(rendered("https://app.test/c/3"), insertSendLog(campaignId, null));

		assertThat(linksOf(templateId)).hasSize(2);
	}

	@Test
	void 고객마다_다른_발송_토큰이_들어간다() {
		long campaignId = insertOneTimeCampaign(insertTemplate(TEMPLATE_BODY));
		long first = insertSendLog(campaignId, null);
		long second = insertSendLog(campaignId, null);

		String a = service.rewrite(rendered("https://app.test/c/1"), first);
		String b = service.rewrite(rendered("https://app.test/c/2"), second);

		assertThat(a).contains("/t/c/" + tokenOf(first) + "/").doesNotContain(tokenOf(second));
		assertThat(b).contains("/t/c/" + tokenOf(second) + "/").doesNotContain(tokenOf(first));
	}

	@Test
	void 워크플로우는_SEND_노드의_templateId로_템플릿을_찾는다() {
		long templateId = insertTemplate(TEMPLATE_BODY);
		long campaignId = jdbc.queryForObject("""
			INSERT INTO campaign (name, type, segment_id, trigger_type, created_by)
			VALUES ('wf', 'WORKFLOW', ?, 'CUSTOMER_REGISTERED', ?) RETURNING campaign_id
			""", Long.class, segmentId, memberId);
		long stepId = jdbc.queryForObject("""
			INSERT INTO workflow_step (campaign_id, node_type, config_json)
			VALUES (?, 'SEND_EMAIL', CAST(? AS jsonb)) RETURNING step_id
			""", Long.class, campaignId, "{\"templateId\": " + templateId + "}");
		long sendLogId = insertSendLog(campaignId, stepId);

		String result = service.rewrite(rendered("https://app.test/c/1"), sendLogId);

		assertThat(linksOf(templateId)).hasSize(2);
		assertThat(result).contains("/t/c/" + tokenOf(sendLogId) + "/");
	}

	@Test
	void 템플릿을_모르는_TEST_발송은_링크를_그대로_두고_픽셀만_넣는다() {
		long sendLogId = insertSendLog(null, null);
		String html = "<a href=\"https://shop.example.com/sale\">세일</a>";

		String result = service.rewrite(html, sendLogId);

		assertThat(result).startsWith(html).contains("/t/o/" + tokenOf(sendLogId) + ".gif");
		assertThat(result).doesNotContain("/t/c/");
	}

	@Test
	void 템플릿에_없는_링크는_등록하지도_바꾸지도_않는다() {
		long templateId = insertTemplate(TEMPLATE_BODY);
		long sendLogId = insertSendLog(insertOneTimeCampaign(templateId), null);
		// 광고 문구 등으로 템플릿 밖에서 들어온 일반 링크
		String html = rendered("https://app.test/c/1") + "<a href=\"https://other.example.com/extra\">추가</a>";

		String result = service.rewrite(html, sendLogId);

		assertThat(result).contains("<a href=\"https://other.example.com/extra\">추가</a>");
		assertThat(linksOf(templateId)).doesNotContainKey("https://other.example.com/extra");
	}

	@Test
	void 템플릿이_수정되면_새_링크를_등록한다() {
		long templateId = insertTemplate(TEMPLATE_BODY);
		long campaignId = insertOneTimeCampaign(templateId);
		service.rewrite(rendered("https://app.test/c/1"), insertSendLog(campaignId, null));

		jdbc.update("UPDATE template SET body = body || ?, updated_at = now() + interval '1 second' WHERE template_id = ?",
			"<a href=\"https://shop.example.com/added\">추가</a>", templateId);
		long sendLogId = insertSendLog(campaignId, null);
		String result = service.rewrite("<a href=\"https://shop.example.com/added\">추가</a>", sendLogId);

		assertThat(linksOf(templateId)).containsKey("https://shop.example.com/added");
		assertThat(result).contains("/t/c/" + tokenOf(sendLogId) + "/" + linksOf(templateId).get("https://shop.example.com/added"));
	}

	@Test
	void 없는_발송_건이면_본문을_그대로_돌려준다() {
		String html = "<a href=\"https://shop.example.com/sale\">세일</a>";

		assertThat(service.rewrite(html, 999_999_999L)).isEqualTo(html);
	}

	@Test
	void 클릭_리다이렉트가_등록된_원본_URL을_찾는다() {
		// rewrite 가 등록한 link_id 로 /t/c 가 원래 주소를 찾을 수 있어야 한다 (엔티티가 풀린 원본으로 저장)
		long templateId = insertTemplate(TEMPLATE_BODY);
		service.rewrite(rendered("https://app.test/c/1"), insertSendLog(insertOneTimeCampaign(templateId), null));

		String original = jdbc.queryForObject("SELECT original_url FROM track_link WHERE link_id = ?", String.class,
			linksOf(templateId).get("https://shop.example.com/new?a=1&b=2"));

		assertThat(original).isEqualTo("https://shop.example.com/new?a=1&b=2");
	}

	// ---- 픽스처 ----

	/** 템플릿을 고객 값으로 렌더링한 결과(렌더러가 하는 일을 흉내 낸 값) */
	private static String rendered(String couponUrl) {
		return TEMPLATE_BODY.replace("{{name|고객}}", "홍길동").replace("{{name}}", "홍길동").replace("{{couponUrl}}", couponUrl);
	}

	private long insertTemplate(String body) {
		return jdbc.queryForObject("""
			INSERT INTO template (channel, name, subject, body, created_by)
			VALUES ('EMAIL', 'link-test', '제목', ?, ?) RETURNING template_id
			""", Long.class, body, memberId);
	}

	private long insertOneTimeCampaign(long templateId) {
		return jdbc.queryForObject("""
			INSERT INTO campaign (name, type, segment_id, template_id, created_by)
			VALUES ('one-time', 'ONE_TIME', ?, ?, ?) RETURNING campaign_id
			""", Long.class, segmentId, templateId, memberId);
	}

	private long insertSendLog(Long campaignId, Long stepId) {
		return jdbc.queryForObject("""
			INSERT INTO send_log (campaign_id, step_id, recipient, channel, kind, priority)
			VALUES (?, ?, 'link-test@withus.local', 'EMAIL', ?, 3) RETURNING send_log_id
			""", Long.class, campaignId, stepId, campaignId == null ? "TEST" : "CAMPAIGN");
	}

	private String tokenOf(long sendLogId) {
		return jdbc.queryForObject("SELECT tracking_token::text FROM send_log WHERE send_log_id = ?", String.class, sendLogId);
	}

	private Map<String, Long> linksOf(long templateId) {
		Map<String, Long> links = new java.util.HashMap<>();
		jdbc.query("SELECT link_id, original_url FROM track_link WHERE template_id = ?",
			rs -> {
				links.put(rs.getString("original_url"), rs.getLong("link_id"));
			}, templateId);
		return links;
	}
}
