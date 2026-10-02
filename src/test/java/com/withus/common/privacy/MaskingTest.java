package com.withus.common.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MaskingTest {

	@Test
	void 이름은_첫_글자만_남긴다() {
		assertThat(Masking.maskName("김민지")).isEqualTo("김**");
		assertThat(Masking.maskName("  박  ")).isEqualTo("박");
		assertThat(Masking.maskName("Kim")).isEqualTo("K**");
		assertThat(Masking.maskName("")).isNull();
		assertThat(Masking.maskName(null)).isNull();
	}
}
