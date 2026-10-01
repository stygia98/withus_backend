package com.withus.tracking;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.withus.tracking.service.TrackingEventPublisher;
import com.withus.tracking.service.TrackingEventService;

/**
 * 추적 API 응답 계약 (API_SPEC 9장): 인증 없이 호출, 처리에 실패해도 픽셀 200 / 리다이렉트 302 를 항상 반환.
 * 저장 로직은 TrackingEventServiceTest 에서 검증하므로 여기서는 발행기·서비스를 목으로 대체한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TrackingControllerTest {

	private static final String HOME = "http://localhost:8080";

	@Autowired
	MockMvc mvc;
	@MockitoBean
	TrackingEventPublisher publisher;
	@MockitoBean
	TrackingEventService service;

	private final String token = UUID.randomUUID().toString();

	@Test
	void 오픈_픽셀은_인증없이_1x1_GIF를_no_store로_돌려주고_이벤트를_접수한다() throws Exception {
		mvc.perform(get("/t/o/{token}.gif", token).header("User-Agent", "Mozilla/5.0"))
			.andExpect(status().isOk())
			.andExpect(content().contentType("image/gif"))
			.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
			.andExpect(content().bytes(java.util.Base64.getDecoder()
				.decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")));

		verify(publisher).open(eq(token), eq("Mozilla/5.0"), anyString(), any(OffsetDateTime.class));
	}

	@Test
	void 오픈_이벤트_접수가_실패해도_픽셀은_200이다() throws Exception {
		doThrow(new IllegalStateException("큐 오류")).when(publisher).open(anyString(), any(), any(), any());

		mvc.perform(get("/t/o/{token}.gif", token)).andExpect(status().isOk());
	}

	@Test
	void 형식이_잘못된_토큰이나_이상한_Accept여도_픽셀은_200이다() throws Exception {
		mvc.perform(get("/t/o/{token}.gif", "not-a-uuid").header("Accept", "text/html"))
			.andExpect(status().isOk())
			.andExpect(content().contentType("image/gif"));
	}

	@Test
	void 클릭은_등록된_원본_URL로_302_이동하고_이벤트를_접수한다() throws Exception {
		when(service.resolveRedirectUrl(5L)).thenReturn("https://example.com/a");

		mvc.perform(get("/t/c/{token}/{linkId}", token, 5).header("User-Agent", "Mozilla/5.0"))
			.andExpect(status().isFound())
			.andExpect(header().string("Location", "https://example.com/a"))
			.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));

		verify(publisher).click(eq(token), eq(5L), eq("Mozilla/5.0"), anyString(), any(OffsetDateTime.class));
	}

	@Test
	void 쿼리스트링의_URL로는_이동하지_않는다_오픈_리다이렉트_방지() throws Exception {
		when(service.resolveRedirectUrl(5L)).thenReturn("https://example.com/a");

		mvc.perform(get("/t/c/{token}/{linkId}", token, 5).param("url", "https://evil.example").param("redirect",
				"https://evil.example"))
			.andExpect(status().isFound())
			.andExpect(header().string("Location", "https://example.com/a"));
	}

	@Test
	void 숫자가_아닌_linkId는_서비스_홈으로_302이고_저장하지_않는다() throws Exception {
		when(service.homeUrl()).thenReturn(HOME);

		mvc.perform(get("/t/c/{token}/{linkId}", token, "abc"))
			.andExpect(status().isFound())
			.andExpect(header().string("Location", HOME));

		verify(publisher, never()).click(anyString(), anyLong(), any(), any(), any());
	}

	@Test
	void 처리_중_오류가_나도_서비스_홈으로_302이다() throws Exception {
		when(service.homeUrl()).thenReturn(HOME);
		when(service.resolveRedirectUrl(7L)).thenThrow(new IllegalStateException("DB 오류"));

		mvc.perform(get("/t/c/{token}/{linkId}", token, 7))
			.andExpect(status().isFound())
			.andExpect(header().string("Location", HOME));
	}

	@Test
	void UA가_없어도_정상_처리한다() throws Exception {
		when(service.resolveRedirectUrl(5L)).thenReturn("https://example.com/a");

		mvc.perform(get("/t/c/{token}/{linkId}", token, 5)).andExpect(status().isFound());

		verify(publisher).click(eq(token), eq(5L), isNull(), anyString(), any(OffsetDateTime.class));
	}
}
