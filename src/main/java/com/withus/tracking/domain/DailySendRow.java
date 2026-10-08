package com.withus.tracking.domain;

import java.time.LocalDate;

import lombok.Getter;

/** 일별 발송 성공 건수 (발송이 없는 날도 0 으로 한 행) */
@Getter
public class DailySendRow {

	private LocalDate date;
	private long sent;
}
