package com.withus.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** AI-03 요약 후처리: 마크다운 정리와 5문장 제한 (PRD 5.3) */
class CampaignReportServiceTest {

	@Test
	void 다섯_문장까지만_남긴다() {
		String text = "첫째입니다. 둘째입니다! 셋째인가요? 넷째입니다. 다섯째입니다. 여섯째입니다.";

		assertThat(CampaignReportService.limitSentences(text))
			.isEqualTo("첫째입니다. 둘째입니다! 셋째인가요? 넷째입니다. 다섯째입니다.");
	}

	@Test
	void 마크다운_기호와_줄바꿈을_정리한다() {
		String text = "## 요약\n- **오픈율 31.2%**로 양호합니다.\n1. 클릭률은 6.8%입니다.\n<b>끝</b>입니다.";

		assertThat(CampaignReportService.limitSentences(text))
			.isEqualTo("요약 오픈율 31.2%로 양호합니다. 클릭률은 6.8%입니다. 끝입니다.");
	}

	@Test
	void 소수점이_있는_숫자는_문장으로_자르지_않는다() {
		assertThat(CampaignReportService.limitSentences("오픈율은 31.2%입니다. 클릭률은 6.8%입니다."))
			.isEqualTo("오픈율은 31.2%입니다. 클릭률은 6.8%입니다.");
	}

	@Test
	void 비어_있으면_빈_문자열() {
		assertThat(CampaignReportService.limitSentences(null)).isEmpty();
		assertThat(CampaignReportService.limitSentences("  \n ")).isEmpty();
	}
}
