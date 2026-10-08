package com.withus.ai.service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.withus.ai.domain.HourlyEngagement;
import com.withus.ai.dto.SendTimeRecommendationResponse;
import com.withus.ai.dto.SendTimeRecommendationResponse.Recommendation;
import com.withus.ai.mapper.SendTimeMapper;
import com.withus.common.exception.BusinessException;
import com.withus.common.exception.CommonErrorCode;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * AI-02 최적 발송 시간 추천 (PRD 5.3, API_SPEC 11장).
 *
 * <ul>
 * <li>시간대 집계·점수·순위는 SQL 과 코드로 정한다. LLM 은 추천 근거 문장만 쓴다.</li>
 * <li>가드레일은 데이터·광고 여부와 관계없이 항상 코드로 적용한다: 시작 08:00~20:00, 시작 + 예상 소요 ≤ 20:50.</li>
 * <li>근거 문장 생성이 실패해도(한도 초과 포함) 추천은 돌려준다 — 서버가 만든 문장으로 대신한다.</li>
 * </ul>
 */
@Service
public class SendTimeRecommendationService {

	private static final Logger log = LoggerFactory.getLogger(SendTimeRecommendationService.class);

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	static final int LOOKBACK_DAYS = 90;
	static final long MIN_EVENTS = 100;
	static final int TOP_N = 3;
	static final int CLICK_WEIGHT = 2;
	static final int OPEN_WEIGHT = 1;
	static final long MAX_TARGET_COUNT = 1_000_000;

	static final LocalTime EARLIEST_START = LocalTime.of(8, 0);
	static final LocalTime LATEST_START = LocalTime.of(20, 0);
	static final LocalTime LATEST_END = LocalTime.of(20, 50);
	static final LocalTime DEFAULT_START = LocalTime.of(10, 0);
	static final String WEEKDAY = "WEEKDAY";

	private static final String SYSTEM_INSTRUCTION = """
		당신은 CRM 마케팅 분석가다. 주어진 발송 시간대 후보마다 이 시간대를 추천하는 이유를 한국어 한 문장(60자 이내)으로 쓴다.
		- 반드시 JSON 하나만 출력한다: {"reasons":["...", ...]} — 후보 순서와 개수를 그대로 지킨다.
		- 각 시간대의 강점을 오픈·클릭 건수로 설명한다. "낮다", "부족하다"처럼 다른 후보보다 못하다는 표현은 쓰지 않는다.
		- "상대 점수" 같은 내부 용어를 쓰지 않는다. 주어진 숫자만 쓰고, 새로운 수치나 예측을 지어내지 않는다.
		""";

	private final SendTimeMapper sendTimeMapper;
	private final LlmClient llmClient;
	private final ObjectMapper objectMapper;
	private final double maxSendRate;
	private Clock clock = Clock.system(SEOUL);

	public SendTimeRecommendationService(SendTimeMapper sendTimeMapper, LlmClient llmClient, ObjectMapper objectMapper,
		@Value("${ses.max-send-rate}") double maxSendRate) {
		this.sendTimeMapper = sendTimeMapper;
		this.llmClient = llmClient;
		this.objectMapper = objectMapper;
		this.maxSendRate = maxSendRate;
	}

	void setClock(Clock clock) {
		this.clock = clock;
	}

	/**
	 * @param targetCount 이번 발송 대상 수 (0 이상)
	 */
	public SendTimeRecommendationResponse recommend(long targetCount) {
		if (targetCount < 0 || targetCount > MAX_TARGET_COUNT) {
			throw new BusinessException(CommonErrorCode.COMMON_INVALID_INPUT,
				"targetCount 는 0~" + MAX_TARGET_COUNT + " 입니다.", null);
		}
		// DB 조회를 먼저 끝내고 LLM 은 트랜잭션 밖에서 부른다 (CLAUDE.md 4장)
		OffsetDateTime since = OffsetDateTime.now(clock).minusDays(LOOKBACK_DAYS);
		List<HourlyEngagement> rows = sendTimeMapper.engagementByDayHour(since);
		long durationSeconds = durationSeconds(sendTimeMapper.countPending() + targetCount);

		long totalEvents = rows.stream().mapToLong(r -> r.getOpens() + r.getClicks()).sum();
		if (totalEvents < MIN_EVENTS) {
			List<Recommendation> fallback = new ArrayList<>();
			if (fitsGuardrail(DEFAULT_START, durationSeconds)) {
				fallback.add(new Recommendation(WEEKDAY, hhmm(DEFAULT_START), null,
					expectedEnd(DEFAULT_START, durationSeconds),
					"최근 %d일 이벤트가 %d건 미만이라 기본값(평일 10:00)을 추천합니다.".formatted(LOOKBACK_DAYS, MIN_EVENTS)));
			}
			return new SendTimeRecommendationResponse(false, fallback);
		}

		List<Candidate> top = rank(rows, durationSeconds);
		List<String> reasons = reasons(top);
		List<Recommendation> result = new ArrayList<>();
		for (int i = 0; i < top.size(); i++) {
			Candidate c = top.get(i);
			result.add(new Recommendation(c.dayCode(), hhmm(c.start()), c.score(), expectedEnd(c.start(), durationSeconds),
				reasons.get(i)));
		}
		return new SendTimeRecommendationResponse(true, result);
	}

	/** 08:00~20:00 시작 시간대만 후보로, 클릭 2 : 오픈 1 가중치로 순위를 매긴 뒤 가드레일을 통과한 상위 3개 */
	static List<Candidate> rank(List<HourlyEngagement> rows, long durationSeconds) {
		List<Candidate> candidates = new ArrayList<>();
		for (HourlyEngagement r : rows) {
			LocalTime start = LocalTime.of(r.getHour(), 0);
			long weight = r.getOpens() * OPEN_WEIGHT + r.getClicks() * CLICK_WEIGHT;
			if (weight > 0 && !start.isBefore(EARLIEST_START) && !start.isAfter(LATEST_START)) {
				candidates.add(new Candidate(DayOfWeek.of(r.getIsoDayOfWeek()), start, r.getOpens(), r.getClicks(),
					weight, 0));
			}
		}
		long maxWeight = candidates.stream().mapToLong(Candidate::weight).max().orElse(1);
		return candidates.stream()
			.filter(c -> fitsGuardrail(c.start(), durationSeconds))
			.sorted(Comparator.comparingLong(Candidate::weight).reversed()
				.thenComparing(Candidate::day)
				.thenComparing(Candidate::start))
			.limit(TOP_N)
			.map(c -> c.withScore(Math.round(c.weight() * 100.0 / maxWeight) / 100.0))
			.toList();
	}

	static boolean fitsGuardrail(LocalTime start, long durationSeconds) {
		if (start.isBefore(EARLIEST_START) || start.isAfter(LATEST_START)) {
			return false;
		}
		return start.toSecondOfDay() + durationSeconds <= LATEST_END.toSecondOfDay();
	}

	long durationSeconds(long messages) {
		if (messages <= 0) {
			return 0;
		}
		return (long) Math.ceil(messages / maxSendRate);
	}

	/** 분 단위로 올림. 가드레일을 통과한 후보만 부르므로 같은 날 20:50 이전이다 */
	static String expectedEnd(LocalTime start, long durationSeconds) {
		long endMinutes = (start.toSecondOfDay() + durationSeconds + 59) / 60;
		return "%02d:%02d".formatted(endMinutes / 60, endMinutes % 60);
	}

	private static String hhmm(LocalTime t) {
		return "%02d:%02d".formatted(t.getHour(), t.getMinute());
	}

	private List<String> reasons(List<Candidate> top) {
		List<String> fallback = top.stream().map(SendTimeRecommendationService::fallbackReason).toList();
		if (top.isEmpty()) {
			return fallback;
		}
		StringBuilder prompt = new StringBuilder("최근 %d일 사람 반응(봇 제외) 기준 추천 후보:\n".formatted(LOOKBACK_DAYS));
		for (int i = 0; i < top.size(); i++) {
			Candidate c = top.get(i);
			prompt.append("%d. %s %s 시작 — 오픈 %d건, 클릭 %d건, 상대 점수 %.2f\n".formatted(i + 1, koreanDay(c.day()),
				hhmm(c.start()), c.opens(), c.clicks(), c.score()));
		}
		try {
			LlmResponse response = llmClient.generate(new LlmRequest(SYSTEM_INSTRUCTION, prompt.toString(), true, 0.3,
				512, objectMapper.writeValueAsString(new Reasons(fallback))));
			if (response.truncated()) {
				return fallback;
			}
			Reasons parsed = objectMapper.readValue(response.text(), Reasons.class);
			if (parsed == null || parsed.reasons() == null || parsed.reasons().size() != top.size()) {
				log.warn("AI-02 근거 문장 개수가 맞지 않아 기본 문장을 씁니다 model={}", response.model());
				return fallback;
			}
			List<String> cleaned = new ArrayList<>();
			for (int i = 0; i < top.size(); i++) {
				String text = parsed.reasons().get(i);
				text = text == null ? "" : text.replaceAll("<[^>]{1,200}>", "").replaceAll("\\s+", " ").strip();
				cleaned.add(text.isEmpty() || text.length() > 200 ? fallback.get(i) : text);
			}
			return cleaned;
		} catch (BusinessException | JacksonException e) {
			// 한도 초과·일시 장애여도 추천 자체는 SQL 결과라 그대로 돌려준다
			log.warn("AI-02 근거 문장 생성 실패, 기본 문장 사용 cause={}", e.getClass().getSimpleName());
			return fallback;
		}
	}

	static String fallbackReason(Candidate c) {
		return "최근 %d일 동안 %s %s대에 오픈 %d건·클릭 %d건으로 반응이 많았습니다.".formatted(LOOKBACK_DAYS,
			koreanDay(c.day()), hhmm(c.start()), c.opens(), c.clicks());
	}

	private static String koreanDay(DayOfWeek day) {
		return day.getDisplayName(TextStyle.FULL, Locale.KOREAN);
	}

	record Candidate(DayOfWeek day, LocalTime start, long opens, long clicks, long weight, double score) {

		String dayCode() {
			return day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toUpperCase(Locale.ROOT);
		}

		Candidate withScore(double newScore) {
			return new Candidate(day, start, opens, clicks, weight, newScore);
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Reasons(List<String> reasons) {
	}
}
