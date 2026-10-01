package com.withus.segment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

/** 대상 수 미리보기 (API_SPEC 4장). 한 번 스캔으로 센다. 동의 수에 suppression 은 빼지 않는다 (Plan Q4) */
@Getter
public class SegmentPreviewResponse {

	@Schema(description = "대상 고객 수 (삭제 고객 제외)")
	private long total;
	@Schema(description = "그중 이메일 수신동의 Y")
	private long emailConsent;
	@Schema(description = "그중 SMS 수신동의 Y")
	private long smsConsent;
	@Schema(description = "그중 휴면")
	private long dormant;
}
