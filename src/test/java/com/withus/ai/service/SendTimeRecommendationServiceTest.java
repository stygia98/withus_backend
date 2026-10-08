package com.withus.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.withus.ai.domain.AiErrorCode;
import com.withus.ai.domain.HourlyEngagement;
import com.withus.ai.dto.SendTimeRecommendationResponse;
import com.withus.ai.dto.SendTimeRecommendationResponse.Recommendation;
import com.withus.ai.mapper.SendTimeMapper;
import com.withus.common.exception.BusinessException;
import com.withus.common.exception.CommonErrorCode;

import tools.jackson.databind.json.JsonMapper;

/** AI-02: 가중치 순위·가드레일·데이터 부족 기본값·근거 문장 대체 (PRD 5.3). DB·Gemini 없이 검증 */
class SendTimeRecommendationServiceTest {

	private final List<HourlyEngagement> rows = new ArrayList<>();
	private long pending;
	private final AtomicInteger llmCalls = new AtomicInteger();
	private LlmClient llm = request -> {
		llmCalls.incrementAndGet();
		return new LlmResponse(request.mockText(), "test", false);
	};

	private SendTimeRecommendationService service(double ratePerSecond) {
		SendTimeMapper mapper = new SendTimeMapper() {
			@Override
			public List<HourlyEngagement> engagementByDayHour(OffsetDateTime since) {
				return rows;
			}

			@Override
			public long countPending() {
				return pending;
			}
		};
		SendTimeRecommendationService s = new SendTimeRecommendationService(mapper, request -> llm.generate(request),
			JsonMapper.builder().build(), ratePerSecond);
		s.setClock(Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneId.of("Asia/Seoul")));
		return s;
	}

	/** 화요일(2) 10시가 클릭이 많아 1위, 수요일(3) 14시는 오픈만 많아 2위 */
	private void enoughData() {
		rows.add(HourlyEngagement.of(2, 10, 20, 40)); // 가중 100
		rows.add(HourlyEngagement.of(3, 14, 90, 0)); // 가중 90
		rows.add(HourlyEngagement.of(4, 9, 10, 10)); // 가중 30
		rows.add(HourlyEngagement.of(5, 11, 5, 5)); // 가중 15
		rows.add(HourlyEngagement.of(1, 7, 500, 500)); // 07시 시작은 허용 안 됨
		rows.add(HourlyEngagement.of(1, 21, 500, 500)); // 21시 시작은 허용 안 됨
	}

	@Test
	void 클릭_2_오픈_1_가중치로_상위_3개를_고르고_허용_시간_밖은_뺀다() {
		enoughData();

		SendTimeRecommendationResponse result = service(1).recommend(100);

		assertThat(result.dataSufficient()).isTrue();
		assertThat(result.recommendations()).extracting(Recommendation::dayOfWeek, Recommendation::startTime,
			Recommendation::score)
			.containsExactly(
				org.assertj.core.groups.Tuple.tuple("TUE", "10:00", 1.0),
				org.assertj.core.groups.Tuple.tuple("WED", "14:00", 0.9),
				org.assertj.core.groups.Tuple.tuple("THU", "09:00", 0.3));
	}

	@Test
	void 예상_종료는_대기_건수와_대상_수를_더해_분_단위로_올린다() {
		enoughData();
		pending = 400;

		// (400 + 600) ÷ 1건/초 = 1000초 = 16분 40초 → 10:17
		Recommendation first = service(1).recommend(600).recommendations().get(0);

		assertThat(first.expectedEndAt()).isEqualTo("10:17");
	}

	@Test
	void 시작과_예상_소요의_합이_20시50분을_넘는_후보는_뺀다() {
		rows.add(HourlyEngagement.of(2, 20, 0, 100)); // 가중 1위지만 20:00 + 1시간 = 21:00
		rows.add(HourlyEngagement.of(2, 19, 0, 10)); // 19:00 + 1시간 = 20:00 통과

		SendTimeRecommendationResponse result = service(1).recommend(3600);

		assertThat(result.recommendations()).extracting(Recommendation::startTime).containsExactly("19:00");
		assertThat(result.recommendations().get(0).expectedEndAt()).isEqualTo("20:00");
	}

	@Test
	void 가드레일_경계값() {
		assertThat(SendTimeRecommendationService.fitsGuardrail(LocalTime.of(20, 0), 50 * 60)).isTrue();
		assertThat(SendTimeRecommendationService.fitsGuardrail(LocalTime.of(20, 0), 50 * 60 + 1)).isFalse();
		assertThat(SendTimeRecommendationService.fitsGuardrail(LocalTime.of(8, 0), 0)).isTrue();
		assertThat(SendTimeRecommendationService.fitsGuardrail(LocalTime.of(7, 0), 0)).isFalse();
	}

	@Test
	void 이벤트가_100건_미만이면_평일_10시_기본값이고_LLM을_부르지_않는다() {
		rows.add(HourlyEngagement.of(2, 10, 60, 39)); // 99건

		SendTimeRecommendationResponse result = service(1).recommend(100);

		assertThat(result.dataSufficient()).isFalse();
		assertThat(result.recommendations()).singleElement().satisfies(r -> {
			assertThat(r.dayOfWeek()).isEqualTo("WEEKDAY");
			assertThat(r.startTime()).isEqualTo("10:00");
			assertThat(r.score()).isNull();
			assertThat(r.reason()).contains("100건 미만");
		});
		assertThat(llmCalls).hasValue(0);
	}

	@Test
	void 대상이_너무_많아_어느_시간도_20시50분_안에_못_끝나면_빈_목록() {
		enoughData();

		// 13시간 이상 걸리는 발송은 08:00 에 시작해도 20:50 을 넘는다
		assertThat(service(1).recommend(13 * 3600).recommendations()).isEmpty();
	}

	@Test
	void LLM_근거_문장을_쓴다() {
		enoughData();
		llm = request -> new LlmResponse("{\"reasons\":[\"클릭이 가장 많아요\",\"오픈이 많아요\",\"꾸준해요\"]}", "test", false);

		assertThat(service(1).recommend(10).recommendations()).extracting(Recommendation::reason)
			.containsExactly("클릭이 가장 많아요", "오픈이 많아요", "꾸준해요");
	}

	@Test
	void LLM이_한도_초과여도_추천은_서버_문장으로_돌려준다() {
		enoughData();
		llm = request -> {
			throw new BusinessException(AiErrorCode.AI_RATE_LIMITED);
		};

		List<Recommendation> recs = service(1).recommend(10).recommendations();

		assertThat(recs).hasSize(3);
		assertThat(recs.get(0).reason()).isEqualTo("최근 90일 동안 화요일 10:00대에 오픈 20건·클릭 40건으로 반응이 많았습니다.");
	}

	@Test
	void LLM_문장_개수가_다르면_서버_문장을_쓴다() {
		enoughData();
		llm = request -> new LlmResponse("{\"reasons\":[\"하나뿐\"]}", "test", false);

		assertThat(service(1).recommend(10).recommendations().get(0).reason()).startsWith("최근 90일 동안 화요일");
	}

	@Test
	void 대상_수가_음수면_400() {
		assertThatThrownBy(() -> service(1).recommend(-1))
			.extracting(e -> ((BusinessException) e).getErrorCode())
			.isEqualTo(CommonErrorCode.COMMON_INVALID_INPUT);
	}
}
