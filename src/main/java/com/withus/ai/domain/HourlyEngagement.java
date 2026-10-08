package com.withus.ai.domain;

import lombok.Getter;

/** 요일(ISO: 1=월 … 7=일)·시(0~23)별 사람 오픈·클릭 수 (Asia/Seoul 기준, 봇·TEST·NOTICE 제외) */
@Getter
public class HourlyEngagement {

	private Integer isoDayOfWeek;
	private Integer hour;
	private Long opens;
	private Long clicks;

	/** 테스트에서 집계 결과를 직접 만들 때 쓴다 */
	public static HourlyEngagement of(int isoDayOfWeek, int hour, long opens, long clicks) {
		HourlyEngagement h = new HourlyEngagement();
		h.isoDayOfWeek = isoDayOfWeek;
		h.hour = hour;
		h.opens = opens;
		h.clicks = clicks;
		return h;
	}
}
