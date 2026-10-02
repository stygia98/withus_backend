package com.withus.campaign.service;

import java.time.Clock;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.withus.campaign.domain.Campaign;
import com.withus.campaign.domain.CampaignErrorCode;
import com.withus.campaign.domain.CampaignStatus;
import com.withus.campaign.domain.CampaignType;
import com.withus.campaign.domain.SendKind;
import com.withus.campaign.domain.Template;
import com.withus.campaign.domain.TemplateErrorCode;
import com.withus.campaign.dto.CampaignEstimateResponse;
import com.withus.campaign.mapper.CampaignMapper;
import com.withus.campaign.mapper.SendLogMapper;
import com.withus.campaign.mapper.TemplateMapper;
import com.withus.common.exception.BusinessException;
import com.withus.common.exception.CommonErrorCode;
import com.withus.common.response.PageResponse;
import com.withus.coupon.domain.CouponErrorCode;
import com.withus.segment.service.SegmentService;

/**
 * 캠페인 생성·수정·조회·예약·시작 (API_SPEC 6장). 일시정지·종료 등 나머지 상태 전이는
 * 후속 작업(W3 상태 전이)에서 CampaignStatus.canTransition 을 통해 처리한다.
 * 워크플로우 캠페인의 SEGMENT_SCHEDULED 즉시 트리거(인스턴스 생성)는 W3 트리거 작업에서 연결한다
 * — 여기서는 캠페인 상태만 ACTIVE 로 바꾼다(연결점만 남겨둠, 추측 구현 금지)
 */
@Service
public class CampaignService {

	/** 템플릿·세그먼트 목록 조회와 같은 상한 */
	private static final int MAX_PAGE_SIZE = 100;

	/** {{couponUrl}} 또는 {{couponUrl|기본값}} — TemplateService 의 치환자 정규식과 같은 형식 */
	private static final Pattern COUPON_URL_PLACEHOLDER = Pattern.compile("\\{\\{\\s*couponUrl(?:\\|[^}]*)?\\s*}}");

	private final CampaignMapper campaignMapper;
	private final TemplateMapper templateMapper;
	private final SendLogMapper sendLogMapper;
	private final SegmentService segmentService;
	private final SendQueueService sendQueueService;
	private final SendWindow sendWindow;
	private final int maxSendRate;
	private Clock clock = Clock.system(ZoneId.of("Asia/Seoul"));

	public CampaignService(CampaignMapper campaignMapper, TemplateMapper templateMapper, SendLogMapper sendLogMapper,
			SegmentService segmentService, SendQueueService sendQueueService,
			@Value("${withus.send-window.start}") String sendWindowStart,
			@Value("${withus.send-window.end}") String sendWindowEnd,
			@Value("${ses.max-send-rate}") int maxSendRate) {
		this.campaignMapper = campaignMapper;
		this.templateMapper = templateMapper;
		this.sendLogMapper = sendLogMapper;
		this.segmentService = segmentService;
		this.sendQueueService = sendQueueService;
		this.sendWindow = new SendWindow(LocalTime.parse(sendWindowStart), LocalTime.parse(sendWindowEnd));
		this.maxSendRate = maxSendRate;
	}

	void setClock(Clock clock) {
		this.clock = clock;
	}

	public PageResponse<Campaign> list(CampaignType type, CampaignStatus status, int page, int size) {
		if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
			throw new BusinessException(CommonErrorCode.COMMON_INVALID_INPUT,
				"page 는 0 이상, size 는 1~" + MAX_PAGE_SIZE, null);
		}
		List<Campaign> content = campaignMapper.findList(type, status, page * size, size);
		long total = campaignMapper.count(type, status);
		return PageResponse.of(content, page, size, total);
	}

	public Campaign getOrThrow(long campaignId) {
		Campaign campaign = campaignMapper.findById(campaignId);
		if (campaign == null) {
			throw new BusinessException(CampaignErrorCode.CAMPAIGN_NOT_FOUND);
		}
		return campaign;
	}

	public Campaign create(Campaign campaign, long memberId) {
		validateTypeFields(campaign);
		requireSegment(campaign.getSegmentId());
		requireCoupon(campaign.getCouponId());
		if (campaign.getType() == CampaignType.ONE_TIME) {
			validateCouponRequirement(campaign);
		}
		campaign.setStatus(CampaignStatus.DRAFT);
		campaign.setCreatedBy(memberId);
		campaignMapper.insert(campaign);
		return campaign;
	}

	public Campaign update(long campaignId, Campaign changes) {
		Campaign existing = getOrThrow(campaignId);
		if (existing.getStatus() != CampaignStatus.DRAFT) {
			throw new BusinessException(CampaignErrorCode.CAMPAIGN_INVALID_STATUS,
				"DRAFT 상태의 캠페인만 수정할 수 있습니다.", null);
		}
		existing.setName(changes.getName());
		existing.setSegmentId(changes.getSegmentId());
		existing.setTemplateId(changes.getTemplateId());
		existing.setCouponId(changes.getCouponId());
		existing.setTriggerType(changes.getTriggerType());
		validateTypeFields(existing);
		requireSegment(existing.getSegmentId());
		requireCoupon(existing.getCouponId());
		if (existing.getType() == CampaignType.ONE_TIME) {
			validateCouponRequirement(existing);
		}
		if (campaignMapper.update(existing) == 0) {
			// 읽은 뒤 시작·예약 요청이 끼어들어 DRAFT 가 아니게 됐다
			throw new BusinessException(CampaignErrorCode.CAMPAIGN_INVALID_STATUS,
				"DRAFT 상태의 캠페인만 수정할 수 있습니다.", null);
		}
		return existing;
	}

	/** DB_SCHEMA ck_campaign_type_fields 를 저장 전에 먼저 걸러 친절한 400 으로 돌려준다 */
	private void validateTypeFields(Campaign campaign) {
		if (campaign.getType() == CampaignType.ONE_TIME) {
			if (campaign.getTemplateId() == null) {
				throw new BusinessException(CommonErrorCode.COMMON_INVALID_INPUT,
					"일회성 캠페인은 템플릿을 선택해야 합니다.", null);
			}
			if (campaign.getTriggerType() != null) {
				throw new BusinessException(CommonErrorCode.COMMON_INVALID_INPUT,
					"일회성 캠페인에는 트리거를 설정할 수 없습니다.", null);
			}
		} else {
			if (campaign.getTriggerType() == null) {
				throw new BusinessException(CommonErrorCode.COMMON_INVALID_INPUT,
					"워크플로우 캠페인은 트리거를 선택해야 합니다.", null);
			}
			if (campaign.getTemplateId() != null || campaign.getCouponId() != null) {
				throw new BusinessException(CommonErrorCode.COMMON_INVALID_INPUT,
					"워크플로우 캠페인은 템플릿·쿠폰을 직접 연결하지 않습니다(SEND 노드별로 설정).", null);
			}
		}
	}

	private void validateCouponRequirement(Campaign campaign) {
		Template template = requireTemplate(campaign.getTemplateId());
		boolean usesCouponUrl = containsCouponUrl(template.getSubject()) || containsCouponUrl(template.getBody());
		if (usesCouponUrl && campaign.getCouponId() == null) {
			throw new BusinessException(CampaignErrorCode.CAMPAIGN_COUPON_REQUIRED);
		}
	}

	/** 워크플로우 SEND 노드 검증(workflow 패키지)에서도 같은 치환자 판정이 필요해 공개한다 */
	public static boolean containsCouponUrl(String text) {
		return text != null && COUPON_URL_PLACEHOLDER.matcher(text).find();
	}

	/** 예상 소요 시간·발송 가능 여부 (API_SPEC 6장 GET /estimate). A/B(선택 기능)는 범위 밖이라 단일 발송만 계산한다 */
	public CampaignEstimateResponse estimate(long campaignId, OffsetDateTime startAt) {
		Campaign campaign = getOrThrow(campaignId);
		requireOneTime(campaign);
		Template template = requireTemplate(campaign.getTemplateId());
		long targetCount = segmentService.findTargetCustomers(campaign.getSegmentId()).size();
		long pendingBacklog = sendLogMapper.countPending();
		long durationSeconds = (long) Math.ceil((pendingBacklog + targetCount) / (double) maxSendRate);

		if (!template.isAd()) {
			OffsetDateTime expectedEndAt = startAt.plusSeconds(durationSeconds);
			return new CampaignEstimateResponse(targetCount, pendingBacklog, maxSendRate, expectedEndAt,
				template.getAdYn(), true, null, null);
		}
		SendWindow.BulkWindowResult result = sendWindow.evaluateBulk(startAt, durationSeconds);
		String reason = result.allowed() ? null : "SEND_WINDOW_EXCEEDED";
		return new CampaignEstimateResponse(targetCount, pendingBacklog, maxSendRate, result.expectedEndAt(),
			template.getAdYn(), result.allowed(), reason, result.nextAvailableAt());
	}

	/** DRAFT → SCHEDULED (일회성만). 오류: CAMPAIGN_SEND_WINDOW_EXCEEDED(422)·COUPON_OUT_OF_PERIOD(422)·CAMPAIGN_INVALID_STATUS(409) */
	public Campaign schedule(long campaignId, OffsetDateTime scheduledAt) {
		Campaign campaign = getOrThrow(campaignId);
		requireOneTime(campaign);
		if (campaign.getStatus() != CampaignStatus.DRAFT) {
			throw new BusinessException(CampaignErrorCode.CAMPAIGN_INVALID_STATUS,
				"DRAFT 상태의 캠페인만 예약할 수 있습니다.", null);
		}
		if (!scheduledAt.isAfter(OffsetDateTime.now(clock))) {
			throw new BusinessException(CommonErrorCode.COMMON_INVALID_INPUT, "예약 시각은 지금보다 뒤여야 합니다.", null);
		}
		checkSendWindowAndCoupon(campaign, scheduledAt);
		if (campaignMapper.schedule(campaignId, scheduledAt) == 0) {
			throw new BusinessException(CampaignErrorCode.CAMPAIGN_INVALID_STATUS,
				"DRAFT 상태의 캠페인만 예약할 수 있습니다.", null);
		}
		campaign.setStatus(CampaignStatus.SCHEDULED);
		campaign.setScheduledAt(scheduledAt);
		return campaign;
	}

	/** SCHEDULED → DRAFT */
	public Campaign cancelSchedule(long campaignId) {
		Campaign campaign = getOrThrow(campaignId);
		if (campaignMapper.cancelSchedule(campaignId) == 0) {
			throw new BusinessException(CampaignErrorCode.CAMPAIGN_INVALID_STATUS,
				"SCHEDULED 상태의 캠페인만 예약을 취소할 수 있습니다.", null);
		}
		campaign.setStatus(CampaignStatus.DRAFT);
		campaign.setScheduledAt(null);
		return campaign;
	}

	/**
	 * DRAFT·SCHEDULED → ACTIVE. 일회성은 지금 바로 큐에 적재한다(SendQueueService.enqueueOneTime).
	 * 워크플로우는 상태만 바꾼다 — SEGMENT_SCHEDULED 즉시 트리거는 W3 트리거 작업이 연결한다
	 */
	public Campaign start(long campaignId) {
		Campaign campaign = getOrThrow(campaignId);
		// 상태를 먼저 본다 — ACTIVE·COMPLETED 캠페인에 시작을 불러도 시간·쿠폰 검사(422)나 대상 조회 없이 바로 409
		if (campaign.getStatus() != CampaignStatus.DRAFT && campaign.getStatus() != CampaignStatus.SCHEDULED) {
			throw new BusinessException(CampaignErrorCode.CAMPAIGN_INVALID_STATUS,
				"DRAFT·SCHEDULED 상태의 캠페인만 시작할 수 있습니다.", null);
		}
		// 선점: 같은 캠페인을 동시에 시작하는 요청 중 한 명만 통과한다(스케줄러 + 사용자 '지금 시작'). 이후 예약 취소·수정이 끼면
		// updated_at 이 바뀌어 마지막 전환이 막힌다 — 적재(수 초~분) 동안 락을 쥐지 않고도 옛 데이터로 ACTIVE 가 되는 걸 막는다(이슈 #52)
		OffsetDateTime claimedAt = campaignMapper.claimStart(campaignId, campaign.getUpdatedAt());
		if (claimedAt == null) {
			throw new BusinessException(CampaignErrorCode.CAMPAIGN_INVALID_STATUS,
				"다른 요청이 이 캠페인을 시작·수정하고 있습니다. 잠시 뒤 다시 시도하세요.", null);
		}
		OffsetDateTime now = OffsetDateTime.now(clock);
		if (campaign.getType() == CampaignType.ONE_TIME) {
			checkSendWindowAndCoupon(campaign, now);
			// 적재를 ACTIVE 전환보다 먼저 한다(PR #31 리뷰 🔴1). 반대로 하면 전환~적재 사이에 CampaignCompleteJob 이
			// "PENDING·SENDING 없음"으로 보고 발송 0건인 채 COMPLETED 로 만들 수 있다. 적재된 건은 캠페인이 ACTIVE 가
			// 되기 전에는 발송 큐가 선점하지 않고(claimBatch), 적재는 uq_send_log_one_time 으로 멱등이라
			// 도중에 실패해도 상태는 그대로(DRAFT·SCHEDULED)라 다시 시작하면 남은 PENDING 을 지우고 새로 적재한다
			// 이전 시작이 적재 도중 실패해 남은 PENDING 을 먼저 지운다(PR #31 재리뷰 🟡1). 그 사이 세그먼트·템플릿 채널이
			// 바뀌었다면 ON CONFLICT DO NOTHING 이 옛 대상·옛 수신처 행을 그대로 남겨 ACTIVE 뒤에 발송되기 때문이다
			sendLogMapper.deleteUnstartedCampaignPending(campaignId);
			Template template = requireTemplate(campaign.getTemplateId());
			List<Long> targetIds = segmentService.findTargetCustomers(campaign.getSegmentId());
			sendQueueService.enqueueOneTime(campaignId, targetIds, template.getChannel(), SendKind.CAMPAIGN);
		}
		if (campaignMapper.start(campaignId, claimedAt) == 0) {
			throw new BusinessException(CampaignErrorCode.CAMPAIGN_INVALID_STATUS,
				"시작하는 동안 캠페인이 수정되거나 예약이 취소되어 시작하지 않았습니다. 내용을 확인하고 다시 시작하세요.", null);
		}
		campaign.setStatus(CampaignStatus.ACTIVE);
		return campaign;
	}

	private Template requireTemplate(Long templateId) {
		Template template = templateId == null ? null : templateMapper.findById(templateId);
		if (template == null) {
			throw new BusinessException(TemplateErrorCode.TEMPLATE_NOT_FOUND);
		}
		return template;
	}

	private void requireCoupon(Long couponId) {
		if (couponId != null && !campaignMapper.existsCoupon(couponId)) {
			throw new BusinessException(CommonErrorCode.COMMON_INVALID_INPUT, "존재하지 않는 쿠폰입니다.", null);
		}
	}

	private void requireSegment(Long segmentId) {
		if (segmentId == null || !campaignMapper.existsSegment(segmentId)) {
			throw new BusinessException(CommonErrorCode.COMMON_INVALID_INPUT, "존재하지 않는 세그먼트입니다.", null);
		}
	}

	/** 광고성이면 20:50 컷오프(SendWindow.evaluateBulk), 쿠폰이 있으면 유효기간을 확인한다 */
	private void checkSendWindowAndCoupon(Campaign campaign, OffsetDateTime startAt) {
		Template template = requireTemplate(campaign.getTemplateId());
		if (template.isAd()) {
			long targetCount = segmentService.findTargetCustomers(campaign.getSegmentId()).size();
			long pendingBacklog = sendLogMapper.countPending();
			long durationSeconds = (long) Math.ceil((pendingBacklog + targetCount) / (double) maxSendRate);
			SendWindow.BulkWindowResult result = sendWindow.evaluateBulk(startAt, durationSeconds);
			if (!result.allowed()) {
				throw new BusinessException(CampaignErrorCode.CAMPAIGN_SEND_WINDOW_EXCEEDED,
					CampaignErrorCode.CAMPAIGN_SEND_WINDOW_EXCEEDED.message(),
					Map.of("nextAvailableAt", result.nextAvailableAt()));
			}
		}
		if (campaign.getCouponId() != null && !Boolean.TRUE.equals(sendLogMapper.isCouponValid(campaign.getCouponId()))) {
			throw new BusinessException(CouponErrorCode.COUPON_OUT_OF_PERIOD);
		}
	}

	private void requireOneTime(Campaign campaign) {
		if (campaign.getType() != CampaignType.ONE_TIME) {
			throw new BusinessException(CommonErrorCode.COMMON_INVALID_INPUT, "일회성 캠페인만 지원합니다.", null);
		}
	}
}
