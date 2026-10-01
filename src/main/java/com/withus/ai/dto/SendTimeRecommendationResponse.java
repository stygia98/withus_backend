package com.withus.ai.dto;

import java.util.List;

/**
 * AI-02 결과 (API_SPEC 11장). 데이터가 부족하면 dayOfWeek = "WEEKDAY"(평일), score = null 인 기본값 1건.
 * 가드레일(시작 08:00~20:00, 시작 + 예상 소요 ≤ 20:50)에 걸리는 후보는 빠지므로 목록이 비어 있을 수 있다.
 */
public record SendTimeRecommendationResponse(boolean dataSufficient, List<Recommendation> recommendations) {

	/**
	 * @param dayOfWeek     MON~SUN, 또는 데이터 부족 시 WEEKDAY
	 * @param startTime     HH:mm
	 * @param score         가장 반응이 좋은 시간대를 1.0 으로 한 상대 점수 (클릭 2 : 오픈 1). 기본값이면 null
	 * @param expectedEndAt HH:mm, (지금 PENDING 건수 + 대상 수) ÷ 초당 발송 한도로 계산한 예상 종료
	 * @param reason        추천 근거 한 문장 (LLM 이 실패하면 서버가 만든 문장)
	 */
	public record Recommendation(String dayOfWeek, String startTime, Double score, String expectedEndAt,
		String reason) {
	}
}
