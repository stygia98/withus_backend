package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.withus.campaign.service.AdCopyInserter;

/** 광고 문구 자동 삽입 (PRD 8.4) */
class AdCopyInserterTest {

	private final AdCopyInserter adCopyInserter = new AdCopyInserter("위드어스", "02-000-0000", "080-000-0000");

	@Test
	void 광고_메일은_제목_앞에_광고_표기가_붙는다() {
		assertThat(adCopyInserter.insertSubject("가을 세일 안내", true)).isEqualTo("(광고) 가을 세일 안내");
	}

	@Test
	void 광고_메일은_본문_하단에_발신자_정보와_수신거부_링크가_붙는다() {
		String body = adCopyInserter.insertEmailBody("<p>본문</p>", true, "https://withus.local/unsubscribe/abc");

		assertThat(body).contains("<p>본문</p>")
			.contains("위드어스").contains("02-000-0000")
			.contains("href=\"https://withus.local/unsubscribe/abc\"");
	}

	@Test
	void 광고_SMS는_앞뒤에_광고_표기와_무료수신거부_번호가_붙는다() {
		String sms = adCopyInserter.insertSms("가을 세일 중!", true);

		assertThat(sms).startsWith("(광고)위드어스 가을 세일 중!").contains("무료수신거부 080-000-0000");
	}

	@Test
	void 비광고_메시지는_그대로_나간다() {
		assertThat(adCopyInserter.insertSubject("안내", false)).isEqualTo("안내");
		assertThat(adCopyInserter.insertEmailBody("<p>본문</p>", false, "https://x")).isEqualTo("<p>본문</p>");
		assertThat(adCopyInserter.insertSms("본문", false)).isEqualTo("본문");
	}
}
