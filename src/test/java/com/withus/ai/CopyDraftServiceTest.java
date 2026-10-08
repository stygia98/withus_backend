package com.withus.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.withus.ai.domain.AiErrorCode;
import com.withus.ai.dto.CopyDraftRequest;
import com.withus.ai.dto.CopyDraftResponse;
import com.withus.ai.service.CopyDraftService;
import com.withus.ai.service.LlmClient;
import com.withus.ai.service.LlmRequest;
import com.withus.ai.service.LlmResponse;
import com.withus.common.exception.BusinessException;

import tools.jackson.databind.json.JsonMapper;

/** AI-01: LLM 응답 해석·정리와 실패 처리 (PRD 5.3). 실제 Gemini 는 부르지 않는다 */
class CopyDraftServiceTest {

	private static final CopyDraftRequest INPUT = new CopyDraftRequest("가을 쿠폰 안내", "20~30대 수도권 우수 고객", "친근하게",
		"5,000원 할인, 10월 한 달");

	private final AtomicReference<LlmRequest> sent = new AtomicReference<>();

	private CopyDraftService serviceReturning(String text, boolean truncated) {
		LlmClient client = request -> {
			sent.set(request);
			return new LlmResponse(text, "test-model", truncated);
		};
		return new CopyDraftService(client, JsonMapper.builder().build());
	}

	private static String drafts(String... subjectBodyPairs) {
		StringBuilder sb = new StringBuilder("{\"drafts\":[");
		for (int i = 0; i < subjectBodyPairs.length; i += 2) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append("{\"subject\":\"").append(subjectBodyPairs[i]).append("\",\"body\":\"")
				.append(subjectBodyPairs[i + 1]).append("\"}");
		}
		return sb.append("]}").toString();
	}

	@Test
	void 세_안을_돌려주고_JSON_응답을_요청한다() {
		CopyDraftResponse result = serviceReturning(drafts("제목1", "본문1", "제목2", "본문2", "제목3", "본문3"), false)
			.generate(INPUT);

		assertThat(result.drafts()).hasSize(3);
		assertThat(result.drafts().get(0).subject()).isEqualTo("제목1");
		assertThat(sent.get().json()).isTrue();
		assertThat(sent.get().prompt()).contains("가을 쿠폰 안내", "5,000원 할인");
	}

	@Test
	void 네_안이_와도_세_안만_쓴다() {
		CopyDraftResponse result = serviceReturning(
			drafts("a", "1", "b", "2", "c", "3", "d", "4"), false).generate(INPUT);

		assertThat(result.drafts()).extracting(CopyDraftResponse.Draft::subject).containsExactly("a", "b", "c");
	}

	@Test
	void 광고_머리말과_HTML과_허용하지_않은_치환자를_지운다() {
		CopyDraftResponse result = serviceReturning(drafts(
			"(광고) {{name}}님께 드리는 혜택", "<p>{{name|회원}}님 안녕하세요</p>\\n\\n\\n\\n쿠폰: {{couponUrl}} 메일 {{email}}",
			"[광고] 제목", "본문", "제목3", "본문3"), false).generate(INPUT);

		CopyDraftResponse.Draft first = result.drafts().get(0);
		assertThat(first.subject()).isEqualTo("{{name|고객}}님께 드리는 혜택");
		assertThat(first.body()).isEqualTo("{{name|고객}}님 안녕하세요\n\n쿠폰:  메일");
		assertThat(result.drafts().get(1).subject()).isEqualTo("제목");
	}

	@Test
	void 본문의_광고_머리말과_수신거부_안내_줄을_지워_광고성_템플릿으로_저장할_수_있게_한다() {
		CopyDraftResponse result = serviceReturning(drafts(
			"제목1", "(광고) 안녕하세요\\n\\n가을 혜택을 드려요\\n무료 수신거부 080-123-4567",
			"제목2", "첫 문단\\n\\n수신을 원치 않으시면 0801234567 로 연락 주세요\\n\\n끝 문단",
			"제목3", "수신거부는 아래 링크에서 하실 수 있어요 02-123-4567"), false).generate(INPUT);

		assertThat(result.drafts()).extracting(CopyDraftResponse.Draft::body).containsExactly(
			"안녕하세요\n\n가을 혜택을 드려요",
			"첫 문단\n\n끝 문단",
			// '수신거부' 단어와 일반 전화번호는 템플릿 검증도 허용한다 (오탐 방지)
			"수신거부는 아래 링크에서 하실 수 있어요 02-123-4567");
	}

	@Test
	void 빈_안을_빼고_세_안이_안_되면_AI_UNAVAILABLE() {
		assertThatThrownBy(() -> serviceReturning(drafts("제목1", "본문1", "", "본문2", "제목3", "<b></b>"), false)
			.generate(INPUT))
			.extracting(e -> ((BusinessException) e).getErrorCode())
			.isEqualTo(AiErrorCode.AI_UNAVAILABLE);
	}

	@Test
	void JSON이_아니면_AI_UNAVAILABLE() {
		assertThatThrownBy(() -> serviceReturning("죄송합니다. 다시 시도해 주세요.", false).generate(INPUT))
			.extracting(e -> ((BusinessException) e).getErrorCode())
			.isEqualTo(AiErrorCode.AI_UNAVAILABLE);
	}

	@Test
	void 출력_한도에서_잘렸으면_AI_UNAVAILABLE() {
		assertThatThrownBy(() -> serviceReturning(drafts("a", "1", "b", "2", "c", "3"), true).generate(INPUT))
			.extracting(e -> ((BusinessException) e).getErrorCode())
			.isEqualTo(AiErrorCode.AI_UNAVAILABLE);
	}

	@Test
	void 입력에_전화번호가_있으면_LLM을_부르지_않고_거절한다() {
		CopyDraftService service = serviceReturning(drafts("a", "1", "b", "2", "c", "3"), false);
		CopyDraftRequest withPhone = new CopyDraftRequest("문의 안내", "전체", "정중하게", "문의는 010-1234-5678");

		assertThatThrownBy(() -> service.generate(withPhone))
			.extracting(e -> ((BusinessException) e).getErrorCode())
			.isEqualTo(AiErrorCode.AI_PII_DETECTED);
		assertThat(sent.get()).isNull();
	}
}
