package com.withus.tracking.service;

import org.springframework.stereotype.Service;

import com.withus.tracking.mapper.TrackEventMapper;

/** 구간 간 연결 인터페이스 구현 — 봇(bot_yn = 'Y')과 TEST·NOTICE 발송은 제외한다 (CLAUDE.md 6장 8번) */
@Service
public class TrackEventRepositoryImpl implements TrackEventRepository {

	private final TrackEventMapper trackEventMapper;

	public TrackEventRepositoryImpl(TrackEventMapper trackEventMapper) {
		this.trackEventMapper = trackEventMapper;
	}

	@Override
	public boolean existsHumanEvent(long sendLogId, String eventType) {
		return trackEventMapper.existsHumanEvent(sendLogId, eventType);
	}
}
