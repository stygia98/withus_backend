package com.withus.tracking.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.withus.tracking.domain.SendStats;

/**
 * 발송 KPI (PRD F-09). 비율은 0~1, 소수 넷째 자리 반올림. 분모가 0 이면 0.
 * 성공률 = sent / attempted, 오픈율·클릭률·전환율 = 고유 고객 수 / sent
 */
public record SendKpi(long attempted, long sent, double successRate, long uniqueOpens, double openRate,
	long uniqueClicks, double clickRate, long couponUsed, double conversionRate) {

	public static SendKpi of(SendStats stats) {
		return new SendKpi(stats.getAttempted(), stats.getSent(), ratio(stats.getSent(), stats.getAttempted()),
			stats.getUniqueOpens(), ratio(stats.getUniqueOpens(), stats.getSent()),
			stats.getUniqueClicks(), ratio(stats.getUniqueClicks(), stats.getSent()),
			stats.getCouponUsed(), ratio(stats.getCouponUsed(), stats.getSent()));
	}

	static double ratio(long numerator, long denominator) {
		if (denominator == 0) {
			return 0.0;
		}
		return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP)
			.doubleValue();
	}
}
