package com.withus.customer.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.withus.customer.mapper.CustomerMapper;

import lombok.RequiredArgsConstructor;

/**
 * 휴면 판정 배치 (PRD F-11) — 일 1회 새벽.
 * 스케줄은 fixedDelay 만 쓰므로(CLAUDE.md 4장) 10분마다 깨어나 03시 이후 그날 첫 회차에만 실행한다.
 * 판정 쿼리는 여러 번 돌아도 결과가 같아서, 재기동으로 같은 날 한 번 더 돌아도 문제없다.
 */
@Component
@RequiredArgsConstructor
public class DormantBatch {

	private static final Logger log = LoggerFactory.getLogger(DormantBatch.class);
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private static final int RUN_HOUR = 3;

	private final CustomerMapper customerMapper;
	private LocalDate lastRunDate;

	@Scheduled(fixedDelay = 10 * 60 * 1000)
	public void tick() {
		LocalDateTime now = LocalDateTime.now(SEOUL);
		if (!shouldRun(now, lastRunDate)) {
			return;
		}
		int[] changed = run();
		lastRunDate = now.toLocalDate();
		log.info("휴면 판정 완료: 휴면 {}명, 해제 {}명", changed[0], changed[1]);
	}

	public static boolean shouldRun(LocalDateTime now, LocalDate lastRunDate) {
		return now.getHour() >= RUN_HOUR && !now.toLocalDate().equals(lastRunDate);
	}

	/** [휴면 처리 수, 해제 수]. 두 UPDATE 는 각각 독립적이라 트랜잭션으로 묶지 않는다 */
	public int[] run() {
		return new int[] {customerMapper.markDormant(), customerMapper.releaseDormant()};
	}
}
