package com.withus.tracking.domain;

import lombok.Getter;

/** 워크플로우 단계 1개의 발송·반응 집계 (정의는 SendStats 와 같다) */
@Getter
public class StepSendStats extends SendStats {

	private Long stepId;
}
