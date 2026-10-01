package com.withus.tracking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
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
import com.withus.tracking.service.TrackEventRepository;
import com.withus.tracking.service.TrackingEventService;

/**
 * 추적 이벤트 저장 규칙 (PRD 8.1, 10.3 완료 기준: 봇 이벤트 분리, 클릭 시 오픈 보정, 없는 토큰 무저장)
 * 로컬 Docker DB 를 쓰고 테스트마다 롤백한다
 */
@SpringBootTest
@Transactional
class TrackingEventServiceTest {

	private static final String BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0 Safari/537.36";

	@Autowired
	TrackingEventService service;
	@Autowired
	TrackEventRepository repository;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	MemberMapper memberMapper;
	@Autowired
	PasswordEncoder passwordEncoder;

	long templateId;
	long[] linkIds;
	long sendLogId;
	String token;
	OffsetDateTime now;

	@BeforeEach
	void setUp() {
		now = OffsetDateTime.now();
		long memberId = insertMember();
		templateId = insertTemplate(memberId);
		linkIds = new long[] { insertLink(templateId, "https://example.com/a", 1),
			insertLink(templateId, "https://example.com/b", 2), insertLink(templateId, "https://example.com/c", 3) };
		// 발송 한 시간 전 — 10초 이내 클릭 규칙에 걸리지 않는다
		sendLogId = insertSendLog("CAMPAIGN", now.minusHours(1));
		token = tokenOf(sendLogId);
	}

	@Test
	void 오픈은_사람_이벤트로_저장된다() {
		service.recordOpen(token, BROWSER_UA, "203.0.113.5", now);

		assertThat(events()).hasSize(1);
		assertThat(events().get(0)).containsEntry("event_type", "OPEN").containsEntry("bot_yn", "N");
	}

	@Test
	void 봇_UA의_오픈은_봇으로_저장된다() {
		service.recordOpen(token, "Mozilla/5.0 (compatible; SecurityScanner/1.0)", "203.0.113.5", now);

		assertThat(events().get(0)).containsEntry("bot_yn", "Y");
	}

	@Test
	void 없는_토큰이나_형식이_잘못된_토큰은_저장하지_않는다() {
		service.recordOpen(UUID.randomUUID().toString(), BROWSER_UA, "203.0.113.5", now);
		service.recordOpen("not-a-uuid", BROWSER_UA, "203.0.113.5", now);
		service.recordClick(UUID.randomUUID().toString(), linkIds[0], BROWSER_UA, "203.0.113.5", now);
		service.recordClick(null, linkIds[0], BROWSER_UA, "203.0.113.5", now);

		assertThat(countAllEvents()).isZero();
	}

	@Test
	void 없는_링크의_클릭은_저장하지_않는다() {
		service.recordClick(token, 999_999_999L, BROWSER_UA, "203.0.113.5", now);

		assertThat(events()).isEmpty();
	}

	@Test
	void 사람_클릭은_오픈이_없으면_오픈도_함께_기록한다() {
		service.recordClick(token, linkIds[0], BROWSER_UA, "203.0.113.5", now);

		assertThat(events()).extracting(e -> e.get("event_type")).containsExactlyInAnyOrder("CLICK", "OPEN");
		assertThat(events()).allSatisfy(e -> assertThat(e).containsEntry("bot_yn", "N"));
	}

	@Test
	void 이미_사람_오픈이_있으면_클릭이_오픈을_중복_기록하지_않는다() {
		service.recordOpen(token, BROWSER_UA, "203.0.113.5", now.minusMinutes(1));
		service.recordClick(token, linkIds[0], BROWSER_UA, "203.0.113.5", now);

		assertThat(events()).extracting(e -> e.get("event_type")).containsExactlyInAnyOrder("OPEN", "CLICK");
	}

	@Test
	void 발송_직후_10초_이내_클릭은_봇이고_오픈을_만들지_않는다() {
		long freshSend = insertSendLog("CAMPAIGN", now.minusSeconds(3));

		service.recordClick(tokenOf(freshSend), linkIds[0], BROWSER_UA, "203.0.113.5", now);

		List<Map<String, Object>> rows = eventsOf(freshSend);
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0)).containsEntry("event_type", "CLICK").containsEntry("bot_yn", "Y");
	}

	@Test
	void 봇_UA의_클릭은_봇이고_오픈을_만들지_않는다() {
		service.recordClick(token, linkIds[0], "SomeCrawler/2.0", "203.0.113.5", now);

		assertThat(events()).hasSize(1);
		assertThat(events().get(0)).containsEntry("event_type", "CLICK").containsEntry("bot_yn", "Y");
	}

	@Test
	void 모든_링크가_1초_안에_클릭되면_앞선_클릭까지_봇으로_바꾼다() {
		service.recordClick(token, linkIds[0], BROWSER_UA, "203.0.113.5", now);
		service.recordClick(token, linkIds[1], BROWSER_UA, "203.0.113.5", now.plusNanos(200_000_000));
		service.recordClick(token, linkIds[2], BROWSER_UA, "203.0.113.5", now.plusNanos(400_000_000));

		assertThat(events()).isNotEmpty();
		assertThat(events()).allSatisfy(e -> assertThat(e).containsEntry("bot_yn", "Y"));
		assertThat(repository.existsHumanEvent(sendLogId, "CLICK")).isFalse();
		assertThat(repository.existsHumanEvent(sendLogId, "OPEN")).isFalse();
	}

	@Test
	void 일부_링크만_클릭하면_사람_클릭으로_남는다() {
		service.recordClick(token, linkIds[0], BROWSER_UA, "203.0.113.5", now);
		service.recordClick(token, linkIds[1], BROWSER_UA, "203.0.113.5", now.plusNanos(200_000_000));

		assertThat(events()).allSatisfy(e -> assertThat(e).containsEntry("bot_yn", "N"));
		assertThat(repository.existsHumanEvent(sendLogId, "CLICK")).isTrue();
	}

	@Test
	void 링크가_1개뿐인_메일의_클릭은_사람_클릭이다() {
		long memberId = insertMember();
		long singleTemplate = insertTemplate(memberId);
		long onlyLink = insertLink(singleTemplate, "https://example.com/only", 1);

		service.recordClick(token, onlyLink, BROWSER_UA, "203.0.113.5", now);

		assertThat(repository.existsHumanEvent(sendLogId, "CLICK")).isTrue();
	}

	@Test
	void TEST_발송의_이벤트는_저장되지만_사람_이벤트_조회에서는_제외된다() {
		long testSend = insertSendLog("TEST", now.minusHours(1));

		service.recordClick(tokenOf(testSend), linkIds[0], BROWSER_UA, "203.0.113.5", now);

		assertThat(eventsOf(testSend)).isNotEmpty();
		assertThat(repository.existsHumanEvent(testSend, "CLICK")).isFalse();
		assertThat(repository.existsHumanEvent(testSend, "OPEN")).isFalse();
	}

	@Test
	void IP는_해시로만_저장하고_UA는_500자로_자른다() {
		service.recordOpen(token, "A".repeat(800), "203.0.113.5", now);

		Map<String, Object> row = events().get(0);
		assertThat((String) row.get("ip_hash")).hasSize(64).doesNotContain("203.0.113.5");
		assertThat((String) row.get("user_agent")).hasSize(500);
	}

	@Test
	void 리다이렉트는_등록된_원본_URL만_쓴다() {
		assertThat(service.resolveRedirectUrl(linkIds[0])).isEqualTo("https://example.com/a");
	}

	@Test
	void 없는_링크나_http가_아닌_주소는_서비스_홈으로_보낸다() {
		long evil = insertLink(templateId, "javascript:alert(1)", 9);

		assertThat(service.resolveRedirectUrl(999_999_999L)).isEqualTo(service.homeUrl());
		assertThat(service.resolveRedirectUrl(evil)).isEqualTo(service.homeUrl());
	}

	// ---- 픽스처 ----

	private long insertMember() {
		Member member = new Member();
		member.setEmail("track-" + UUID.randomUUID() + "@withus.local");
		member.setPassword(passwordEncoder.encode("pw-for-test"));
		member.setName("추적테스트");
		member.setRole(Role.MANAGER);
		memberMapper.insert(member);
		return member.getMemberId();
	}

	private long insertTemplate(long memberId) {
		return jdbc.queryForObject("""
			INSERT INTO template (channel, name, subject, body, created_by)
			VALUES ('EMAIL', 'track-test', '제목', '<a href="https://example.com/a">a</a>', ?)
			RETURNING template_id
			""", Long.class, memberId);
	}

	private long insertLink(long templateId, String url, int order) {
		return jdbc.queryForObject("""
			INSERT INTO track_link (template_id, original_url, link_order) VALUES (?, ?, ?) RETURNING link_id
			""", Long.class, templateId, url, order);
	}

	private long insertSendLog(String kind, OffsetDateTime sentAt) {
		return jdbc.queryForObject("""
			INSERT INTO send_log (recipient, channel, kind, priority, status, sent_at)
			VALUES ('track-test@withus.local', 'EMAIL', ?, 3, 'SENT', ?)
			RETURNING send_log_id
			""", Long.class, kind, sentAt);
	}

	private String tokenOf(long sendLogId) {
		return jdbc.queryForObject("SELECT tracking_token::text FROM send_log WHERE send_log_id = ?", String.class,
			sendLogId);
	}

	private List<Map<String, Object>> events() {
		return eventsOf(sendLogId);
	}

	private List<Map<String, Object>> eventsOf(long id) {
		return jdbc.queryForList("SELECT event_type, link_id, bot_yn, ip_hash, user_agent FROM track_event "
			+ "WHERE send_log_id = ? ORDER BY event_id", id);
	}

	private int countAllEvents() {
		return jdbc.queryForObject("SELECT count(*) FROM track_event WHERE send_log_id = ?", Integer.class,
			sendLogId);
	}
}
