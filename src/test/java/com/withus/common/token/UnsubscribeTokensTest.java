package com.withus.common.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

/** 수신거부 토큰 (PRD 8.3) */
class UnsubscribeTokensTest {

	private final UnsubscribeTokens tokens = new UnsubscribeTokens("test-secret-0123456789-0123456789-abcdef");

	@Test
	void 발급한_토큰은_검증되고_내용을_돌려준다() {
		String token = tokens.issue(5012L, 318L);

		assertThat(token).doesNotContain("=", "+", "/"); // URL 경로에 그대로 쓸 수 있다
		assertThat(tokens.verify(token)).contains(new UnsubscribeTokens.Payload(5012L, 318L));
	}

	@Test
	void 내용을_바꾸거나_다른_키로_만든_토큰은_실패한다() {
		String token = tokens.issue(5012L, 318L);
		String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
		String otherCustomer = Base64.getUrlEncoder().withoutPadding()
			.encodeToString(decoded.replaceFirst(":318:", ":319:").getBytes(StandardCharsets.UTF_8));

		assertThat(tokens.verify(otherCustomer)).isEmpty();
		assertThat(new UnsubscribeTokens("another-secret-0123456789-0123456789").verify(token)).isEmpty();
	}

	@Test
	void 형식이_틀린_토큰은_예외_없이_실패한다() {
		for (String bad : new String[] {null, "", "!!!", "YWJj", encode("1:2"), encode("a:b:c"), encode("1:2:3:4")}) {
			assertThat(tokens.verify(bad)).as(String.valueOf(bad)).isEmpty();
		}
	}

	@Test
	void 키가_32자보다_짧으면_기동하지_않는다() {
		assertThatThrownBy(() -> new UnsubscribeTokens("short")).isInstanceOf(IllegalStateException.class);
	}

	private static String encode(String raw) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}
}
