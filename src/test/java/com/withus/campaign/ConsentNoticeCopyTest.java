package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

import com.withus.campaign.service.ConsentNoticeCopy;

/** F-12 안내 고정 문구 — DB 없이 실행되는 순수 단위 테스트. 필수 요소 세 가지와 (광고) 부재를 확인한다 (PRD 5.1 F-12, 8.4) */
class ConsentNoticeCopyTest {

	private final ConsentNoticeCopy copy = new ConsentNoticeCopy("위드어스", "080-000-0000");
	// 서울 기준 2024-10-07 09:00 (UTC 로는 같은 날 00:00)
	private final OffsetDateTime consentAt = OffsetDateTime.parse("2024-10-07T09:00:00+09:00");

	@Test
	void 메일은_전송자_동의_날짜_동의_사실_수신거부_링크를_담고_광고_표시가_없다() {
		String subject = copy.emailSubject();
		String body = copy.emailBody(consentAt, "https://withus.local/unsubscribe/abc");

		assertThat(subject).contains("위드어스").doesNotContain("(광고)");
		assertThat(body).contains("위드어스").contains("2024년 10월 7일").contains("이메일 광고성 정보 수신에 동의")
			.contains("href=\"https://withus.local/unsubscribe/abc\"").contains("수신거부")
			.doesNotContain("(광고)");
	}

	@Test
	void SMS는_전송자_동의_날짜_동의_사실_무료수신거부_번호를_담고_광고_표시가_없다() {
		String sms = copy.sms(consentAt);

		assertThat(sms).startsWith("위드어스").contains("2024년 10월 7일").contains("문자 광고성 정보 수신에 동의")
			.contains("무료수신거부 080-000-0000").doesNotContain("(광고)");
	}

	@Test
	void 동의_날짜는_UTC_기준이_아니라_서울_기준으로_보여준다() {
		// 2024-10-06T16:00Z 는 서울로 10월 7일 01:00 — 날짜가 하루 어긋나면 안 된다
		OffsetDateTime utc = OffsetDateTime.parse("2024-10-06T16:00:00Z");

		assertThat(copy.sms(utc)).contains("2024년 10월 7일");
		assertThat(copy.emailBody(utc, "https://withus.local/unsubscribe/abc")).contains("2024년 10월 7일");
	}

	@Test
	void 메일_본문의_링크와_전송자명은_HTML_이스케이프된다() {
		ConsentNoticeCopy risky = new ConsentNoticeCopy("<b>위드</b>", "080-000-0000");

		String body = risky.emailBody(consentAt, "https://withus.local/u?a=1&b=\"2\"");

		assertThat(body).doesNotContain("<b>위드</b>").contains("&lt;b&gt;").doesNotContain("b=\"2\"");
	}
}
