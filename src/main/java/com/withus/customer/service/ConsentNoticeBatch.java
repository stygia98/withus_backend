package com.withus.customer.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.withus.campaign.domain.SendKind;
import com.withus.campaign.domain.SendLog;
import com.withus.campaign.service.SendQueueService;
import com.withus.common.domain.Channel;
import com.withus.customer.mapper.CustomerMapper;

import lombok.RequiredArgsConstructor;

/**
 * 수신동의 2년 주기 확인 안내 (PRD F-12) — 일 1회 새벽, 휴면 배치와 같은 방식(10분마다 깨어나 03시 이후 그날 첫 회차).
 * 1) SENT 된 NOTICE 로 consent_notified_at 갱신 2) 채널별 대상을 공통 발송 큐에 NOTICE 로 적재.
 * 08:00~20:50 시간 제한·발송 직전 재확인·안내 본문 렌더링은 발송 작업(팀원2)이 한다.
 * 적재는 SendQueueService 가 500건씩 자기 트랜잭션으로 하므로 여기서는 트랜잭션을 열지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ConsentNoticeBatch {

	private static final Logger log = LoggerFactory.getLogger(ConsentNoticeBatch.class);
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private static final int CHUNK = 500;

	private final CustomerMapper customerMapper;
	private final SendQueueService sendQueueService;
	private LocalDate lastRunDate;

	@Scheduled(fixedDelay = 10 * 60 * 1000)
	public void tick() {
		LocalDateTime now = LocalDateTime.now(SEOUL);
		if (!DormantBatch.shouldRun(now, lastRunDate)) {
			return;
		}
		run();
		lastRunDate = now.toLocalDate();
	}

	/** 채널별 새로 적재된 건수(PENDING + SKIPPED). 재실행해도 보류 중인 안내는 다시 넣지 않는다 */
	public int run() {
		int synced = customerMapper.syncConsentNotifiedAt();
		int total = 0;
		for (Channel channel : Channel.values()) {
			int queued = 0;
			long afterId = 0;
			List<Long> ids;
			while (!(ids = customerMapper.findConsentNoticeTargets(channel, afterId, CHUNK)).isEmpty()) {
				queued += sendQueueService.enqueueOneTime(null, ids, channel, SendKind.NOTICE,
					SendLog.PRIORITY_WORKFLOW_OR_NOTICE);
				afterId = ids.get(ids.size() - 1);
			}
			log.info("수신동의 확인 안내 적재: {} {}건", channel, queued);
			total += queued;
		}
		log.info("수신동의 확인 안내 완료: 적재 {}건, 직전 안내 일시 갱신 {}명", total, synced);
		return total;
	}
}
