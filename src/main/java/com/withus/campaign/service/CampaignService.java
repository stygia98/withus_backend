package com.withus.campaign.service;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.withus.campaign.domain.Campaign;
import com.withus.campaign.domain.CampaignErrorCode;
import com.withus.campaign.domain.CampaignStatus;
import com.withus.campaign.domain.CampaignType;
import com.withus.campaign.domain.Template;
import com.withus.campaign.mapper.CampaignMapper;
import com.withus.campaign.mapper.TemplateMapper;
import com.withus.common.exception.BusinessException;
import com.withus.common.exception.CommonErrorCode;
import com.withus.common.response.PageResponse;

/**
 * 캠페인 생성·수정·조회 (API_SPEC 6장). 예약·시작·일시정지 등 상태 전이와 발송 적재는
 * 후속 작업(캠페인 3/4·4/4, W3 상태 전이)에서 CampaignStatus.canTransition 을 통해 처리한다
 */
@Service
public class CampaignService {

	/** 템플릿·세그먼트 목록 조회와 같은 상한 */
	private static final int MAX_PAGE_SIZE = 100;

	/** {{couponUrl}} 또는 {{couponUrl|기본값}} — TemplateService 의 치환자 정규식과 같은 형식 */
	private static final Pattern COUPON_URL_PLACEHOLDER = Pattern.compile("\\{\\{\\s*couponUrl(?:\\|[^}]*)?\\s*}}");

	private final CampaignMapper campaignMapper;
	private final TemplateMapper templateMapper;

	public CampaignService(CampaignMapper campaignMapper, TemplateMapper templateMapper) {
		this.campaignMapper = campaignMapper;
		this.templateMapper = templateMapper;
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
		if (existing.getType() == CampaignType.ONE_TIME) {
			validateCouponRequirement(existing);
		}
		campaignMapper.update(existing);
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
		Template template = templateMapper.findById(campaign.getTemplateId());
		boolean usesCouponUrl = containsCouponUrl(template.getSubject()) || containsCouponUrl(template.getBody());
		if (usesCouponUrl && campaign.getCouponId() == null) {
			throw new BusinessException(CampaignErrorCode.CAMPAIGN_COUPON_REQUIRED);
		}
	}

	private boolean containsCouponUrl(String text) {
		return text != null && COUPON_URL_PLACEHOLDER.matcher(text).find();
	}
}
