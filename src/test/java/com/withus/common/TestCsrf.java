package com.withus.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import jakarta.servlet.http.Cookie;

/**
 * 테스트용 CSRF — 실제 GET /api/v1/auth/csrf 로 받은 쿠키를 쿠키·X-XSRF-TOKEN 헤더로 붙인다.
 * SecurityMockMvcRequestPostProcessors.csrf() 는 공유 Spring 컨텍스트의 CSRF 저장소를 바꿔 끼워
 * 실행 순서에 따라 다른 쿠키 방식 테스트를 403 으로 깨뜨리므로 쓰지 않는다 (docs/workflow-git.md 테스트 작성 규칙).
 *
 * <pre>
 * RequestPostProcessor csrf;            // @BeforeEach: csrf = TestCsrf.issue(mvc);
 * mvc.perform(post(...).with(auth).with(csrf))
 * </pre>
 */
public final class TestCsrf {

	private TestCsrf() {
	}

	/** 쿠키를 한 번 발급받아, 이후 요청마다 같은 쿠키·헤더를 붙이는 후처리기를 돌려준다 */
	public static RequestPostProcessor issue(MockMvc mvc) throws Exception {
		Cookie xsrf = mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		if (xsrf == null) {
			throw new IllegalStateException("XSRF-TOKEN 쿠키가 없습니다. 앞선 테스트가 with(csrf())로 CSRF 저장소를 바꿨을 수 있습니다.");
		}
		return request -> {
			request.setCookies(xsrf);
			request.addHeader("X-XSRF-TOKEN", xsrf.getValue());
			return request;
		};
	}
}
