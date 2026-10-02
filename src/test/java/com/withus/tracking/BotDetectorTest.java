package com.withus.tracking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.withus.tracking.config.TrackingProperties;
import com.withus.tracking.service.BotDetector;

/** 봇 판정 3규칙 (PRD 8.1, 10.3 완료 기준: 봇 이벤트 분리) */
class BotDetectorTest {

	private final BotDetector detector = new BotDetector(
		new TrackingProperties("http://localhost:8080", 10, List.of("bot", "Crawler", " preview "), "salt", 1, List.of(" CuBot ")));

	private final OffsetDateTime sentAt = OffsetDateTime.parse("2026-10-05T09:00:00+09:00");

	@Test
	void UA_키워드는_대소문자를_무시하고_부분_일치로_판정한다() {
		assertThat(detector.isBotUserAgent("Mozilla/5.0 (compatible; Googlebot/2.1)")).isTrue();
		assertThat(detector.isBotUserAgent("SOME-CRAWLER/1.0")).isTrue();
		assertThat(detector.isBotUserAgent("LinkPreview Agent")).isTrue();
	}

	@Test
	void 예외_목록의_기기명은_대소문자와_공백을_무시하고_UA_에서_지운_뒤_판정한다() {
		assertThat(detector.isBotUserAgent("Android 10; CUBOT X30")).isFalse();
		// 예외 기기명을 지워도 다른 곳에 키워드가 있으면 봇
		assertThat(detector.isBotUserAgent("Android 10; CUBOT X30; SecurityBot/1.0")).isTrue();
	}

	@Test
	void 일반_브라우저_UA는_봇이_아니다() {
		assertThat(detector.isBotUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0 Safari/537.36"))
			.isFalse();
	}

	@Test
	void UA가_없거나_비어_있으면_봇으로_보지_않는다() {
		assertThat(detector.isBotUserAgent(null)).isFalse();
		assertThat(detector.isBotUserAgent("  ")).isFalse();
	}

	@Test
	void 발송_후_10초_이내_클릭은_봇이다_경계값_포함() {
		assertThat(detector.isImmediateClick(sentAt, sentAt.plusSeconds(3))).isTrue();
		assertThat(detector.isImmediateClick(sentAt, sentAt.plusSeconds(10))).isTrue();
	}

	@Test
	void 발송_후_10초를_넘긴_클릭은_봇이_아니다() {
		assertThat(detector.isImmediateClick(sentAt, sentAt.plusSeconds(10).plusNanos(1_000_000))).isFalse();
		assertThat(detector.isImmediateClick(sentAt, sentAt.plusMinutes(5))).isFalse();
	}

	@Test
	void 클릭이_발송_시각보다_앞서면_봇으로_본다() {
		assertThat(detector.isImmediateClick(sentAt, sentAt.minusSeconds(1))).isTrue();
	}

	@Test
	void 발송_시각을_모르면_10초_규칙은_적용하지_않는다() {
		assertThat(detector.isImmediateClick(null, sentAt)).isFalse();
	}

	@Test
	void 모든_링크가_짧은_시간에_클릭되면_봇이다() {
		assertThat(detector.isBurstClick(3, 3)).isTrue();
		assertThat(detector.isBurstClick(2, 2)).isTrue();
	}

	@Test
	void 일부_링크만_클릭되면_봇이_아니다() {
		assertThat(detector.isBurstClick(3, 2)).isFalse();
		assertThat(detector.isBurstClick(3, 1)).isFalse();
	}

	@Test
	void 링크가_1개뿐이면_전부_클릭_규칙은_적용하지_않는다() {
		assertThat(detector.isBurstClick(1, 1)).isFalse();
		assertThat(detector.isBurstClick(0, 0)).isFalse();
	}
}
