package com.withus.tracking;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.withus.tracking.service.BotDetector;

/**
 * 실제 설정(application.yml 의 withus.tracking.bot-user-agent-keywords)으로 판정한다.
 * 키워드를 바꿀 때 사람의 열람·클릭을 봇으로 지우지 않는지 확인하는 회귀 테스트다.
 *
 * <p>근거(2026-10-01 조사, docs/roles/member3-verification.md 4장):
 * <ul>
 * <li>Gmail 이미지 프록시: 메일이 표시될 때 Google 이 대신 이미지를 가져온다. UA 끝이 "(via ggpht.com GoogleImageProxy)".
 * 사람의 열람이므로 봇이 아니다 — "proxy" 같은 키워드를 넣으면 Gmail 오픈이 모두 사라진다.</li>
 * <li>Apple Mail 개인정보 보호: 도착 시 미리 가져오며 UA 는 "Mozilla/5.0" 뿐이라 UA 로 구별할 수 없다(오픈율 한계 안내로 대응).</li>
 * <li>Microsoft Safe Links: 배달 전 검사 UA 가 공식 문서에 없다. UA 가 아니라 10초 규칙·1초 전체 클릭 규칙으로 대응한다.</li>
 * </ul>
 */
@SpringBootTest
class BotUserAgentConfigTest {

	@Autowired
	BotDetector detector;

	@Test
	void Gmail_이미지_프록시_열람은_봇이_아니다() {
		assertThat(detector.isBotUserAgent(
			"Mozilla/5.0 (Windows NT 5.1; rv:11.0) Gecko Firefox/11.0 (via ggpht.com GoogleImageProxy)")).isFalse();
	}

	@Test
	void Apple_Mail_개인정보_보호_요청은_UA로_구별하지_않는다() {
		assertThat(detector.isBotUserAgent("Mozilla/5.0")).isFalse();
	}

	@Test
	void 일반_메일_앱과_브라우저의_클릭은_봇이_아니다() {
		assertThat(detector.isBotUserAgent(
			"Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"))
			.isFalse();
		assertThat(detector.isBotUserAgent(
			"Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36 NAVER(inapp; search; 2000; 12.6.0)"))
			.isFalse();
		assertThat(detector.isBotUserAgent(
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36 Edg/126.0"))
			.isFalse();
	}

	@Test
	void 키워드를_포함한_사람_기기명은_예외_목록으로_뺀다() {
		// 'bot' 부분 일치로 지워지던 사람 UA (PR #30·#38 리뷰). 예외 목록(bot-user-agent-allow-list)에 있는 기기명만 뺀다
		assertThat(detector.isBotUserAgent(
			"Mozilla/5.0 (Linux; Android 10; CUBOT X30) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"))
			.isFalse();
		assertThat(detector.isBotUserAgent("Mozilla/5.0 (Linux; Android 11; Cubot_KingKong) Chrome/126.0 Mobile Safari/537.36"))
			.isFalse();
	}

	@Test
	void 소문자로_바꾸면_길이가_달라지는_문자가_있어도_예외_없이_판정한다() {
		// İ(U+0130)는 소문자화하면 2글자가 된다 — 원본과 소문자 문자열의 위치를 섞어 쓰면 StringIndexOutOfBoundsException
		assertThat(detector.isBotUserAgent("İ Googlebot")).isTrue();
		assertThat(detector.isBotUserAgent("İİİ Mozilla/5.0 (Windows NT 10.0) Chrome/126.0")).isFalse();
	}

	@Test
	void 스스로_봇이라고_밝힌_요청은_봇이다() {
		assertThat(detector.isBotUserAgent("Mozilla/5.0 (compatible; bingbot/2.0; +http://www.bing.com/bingbot.htm)"))
			.isTrue();
		assertThat(detector.isBotUserAgent("Slackbot-LinkExpanding 1.0 (+https://api.slack.com/robots)")).isTrue();
		assertThat(detector.isBotUserAgent("Mozilla/5.0 (compatible; AhrefsBot/7.0; +http://ahrefs.com/robot/)")).isTrue();
		assertThat(detector.isBotUserAgent("Mozilla/5.0 (compatible; Baiduspider/2.0)")).isTrue();
		assertThat(detector.isBotUserAgent("Mozilla/5.0 (compatible; SecurityScanner/1.0)")).isTrue();
		// 대문자 UA·키워드 뒤에 글자가 이어지는 UA 도 부분 일치라 놓치지 않는다
		assertThat(detector.isBotUserAgent("GOOGLEBOT/2.1")).isTrue();
		assertThat(detector.isBotUserAgent("LINKEDINBOT/1.0 (compatible)")).isTrue();
		assertThat(detector.isBotUserAgent("acme-crawlers/3.2")).isTrue();
	}
}
