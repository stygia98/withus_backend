package com.withus.campaign.service;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.withus.campaign.domain.CustomerPlaceholderSource;
import com.withus.campaign.domain.SendKind;
import com.withus.campaign.domain.SendLog;
import com.withus.campaign.domain.Template;
import com.withus.campaign.mapper.SendLogMapper;
import com.withus.campaign.service.messaging.OutboundMessage;
import com.withus.common.domain.Channel;
import com.withus.common.render.PlaceholderRenderer;
import com.withus.coupon.service.CouponService;
import com.withus.customer.service.ConsentService;
import com.withus.tracking.service.TrackingLinkService;

/**
 * 재확인(SendRecheck)을 통과한 건만 렌더링한다(발송 큐 Plan 9장). 순서: 쿠폰 발급(연결된 경우만, 유효기간 밖이면
 * SKIPPED COUPON_INVALID) → 치환자 치환 → 광고 문구 삽입 → (EMAIL) 추적 치환 → (EMAIL) List-Unsubscribe 헤더.
 * kind=TEST 는 고객이 없어 쿠폰 발급·추적 치환을 하지 않고 고정 샘플 값과 예시 쿠폰 링크로 채운다(API_SPEC 5장).
 * kind=NOTICE(F-12 수신동의 확인 안내)는 템플릿·캠페인이 없어 분기부터 나눈다 — 쿠폰 발급·추적 치환 없이 고정 문구를 쓰고,
 * 동의 일시를 못 찾으면 SKIPPED(CONSENT_DATE_MISSING)로 종결한다(PL 결정, 이슈 #81).
 */
@Component
public class MessageComposer {

	private final SendLogMapper sendLogMapper;
	private final CouponService couponService;
	private final PlaceholderRenderer placeholderRenderer;
	private final AdCopyInserter adCopyInserter;
	private final TrackingLinkService trackingLinkService;
	private final ConsentService consentService;
	private final ConsentNoticeCopy consentNoticeCopy;
	private final String trackingBaseUrl;

	public MessageComposer(SendLogMapper sendLogMapper, CouponService couponService,
			PlaceholderRenderer placeholderRenderer, AdCopyInserter adCopyInserter,
			TrackingLinkService trackingLinkService, ConsentService consentService, ConsentNoticeCopy consentNoticeCopy,
			@Value("${withus.tracking.base-url}") String trackingBaseUrl) {
		this.sendLogMapper = sendLogMapper;
		this.couponService = couponService;
		this.placeholderRenderer = placeholderRenderer;
		this.adCopyInserter = adCopyInserter;
		this.trackingLinkService = trackingLinkService;
		this.consentService = consentService;
		this.consentNoticeCopy = consentNoticeCopy;
		this.trackingBaseUrl = trackingBaseUrl;
	}

	/**
	 * @param unsubscribeUrl         본문 링크 — 확인 화면 /unsubscribe/{token}
	 * @param unsubscribeOneClickUrl List-Unsubscribe 헤더 전용 — 메일 앱이 바로 POST 하는
	 *                                /api/v1/unsubscribe/one-click/{token}(RFC 8058, Plan 15장 B3)
	 * @return 렌더링된 메시지. 비어 있으면 쿠폰이 유효기간 밖이라 SKIPPED(COUPON_INVALID)로, 또는 NOTICE 의 동의 일시가 없어
	 *         SKIPPED(CONSENT_DATE_MISSING)로 이미 기록했다는 뜻
	 */
	public Optional<OutboundMessage> compose(SendLog sendLog, Template template, String unsubscribeUrl,
			String unsubscribeOneClickUrl) {
		if (sendLog.getKind() == SendKind.NOTICE) {
			return composeNotice(sendLog, unsubscribeUrl, unsubscribeOneClickUrl);
		}
		boolean test = sendLog.getKind() == SendKind.TEST;
		Long couponId = test ? null
			: sendLog.getStepId() != null
				? sendLogMapper.findCouponIdByStepId(sendLog.getStepId())
				: sendLogMapper.findCouponIdByCampaignId(sendLog.getCampaignId());

		String couponUrl = test ? trackingBaseUrl + "/c/example" : "";
		if (couponId != null) {
			if (!Boolean.TRUE.equals(sendLogMapper.isCouponValid(couponId))) {
				sendLogMapper.recordSkippedCoupon(sendLog.getSendLogId());
				return Optional.empty();
			}
			UUID token = couponService.issue(couponId, sendLog.getCustomerId(), sendLog.getSendLogId());
			couponUrl = trackingBaseUrl + "/c/" + token;
		}

		Map<String, String> values = test
			? placeholderValues(TemplatePreviewService.FIXED_SAMPLE, couponUrl)
			: placeholderValues(sendLog.getCustomerId(), couponUrl);
		boolean isAd = template.isAd();

		if (sendLog.getChannel() == Channel.EMAIL) {
			String subject = adCopyInserter.insertSubject(placeholderRenderer.render(template.getSubject(), values),
				isAd);
			String rendered = placeholderRenderer.renderHtml(template.getBody(), values);
			String withAd = adCopyInserter.insertEmailBody(rendered, isAd, unsubscribeUrl);
			String body = test ? withAd : trackingLinkService.rewrite(withAd, sendLog.getSendLogId());
			Map<String, String> headers = Map.of(
				"List-Unsubscribe", "<" + unsubscribeOneClickUrl + ">",
				"List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
			return Optional.of(new OutboundMessage(Channel.EMAIL, sendLog.getRecipient(), subject, body, headers));
		}

		String rendered = placeholderRenderer.render(template.getBody(), values);
		String body = adCopyInserter.insertSms(rendered, isAd);
		return Optional.of(new OutboundMessage(Channel.SMS, sendLog.getRecipient(), null, body, Map.of()));
	}

	/**
	 * F-12 안내(PL 결정 #81). 발송 직전 재확인(동의·suppression·삭제·시간창)을 통과한 건에서만 불린다 — findConsentAt 은
	 * suppression 을 보지 않으므로 이 전제가 필요하다. 동의 일시가 없으면(삭제·동의 N·없는 고객) 법정 요건(동의 사실·날짜)을
	 * 못 채우므로 NPE·재시도가 아니라 SKIPPED 로 끝낸다. 쿠폰 발급·추적 치환은 하지 않는다(수신거부 링크 포함)
	 */
	private Optional<OutboundMessage> composeNotice(SendLog sendLog, String unsubscribeUrl,
			String unsubscribeOneClickUrl) {
		Optional<OffsetDateTime> consentAt = consentService.findConsentAt(sendLog.getCustomerId(), sendLog.getChannel());
		if (consentAt.isEmpty()) {
			sendLogMapper.recordSkipped(sendLog.getSendLogId(), "CONSENT_DATE_MISSING");
			return Optional.empty();
		}
		if (sendLog.getChannel() == Channel.EMAIL) {
			Map<String, String> headers = Map.of(
				"List-Unsubscribe", "<" + unsubscribeOneClickUrl + ">",
				"List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
			return Optional.of(new OutboundMessage(Channel.EMAIL, sendLog.getRecipient(),
				consentNoticeCopy.emailSubject(), consentNoticeCopy.emailBody(consentAt.get(), unsubscribeUrl), headers));
		}
		return Optional.of(new OutboundMessage(Channel.SMS, sendLog.getRecipient(), null,
			consentNoticeCopy.sms(consentAt.get()), Map.of()));
	}

	private Map<String, String> placeholderValues(Long customerId, String couponUrl) {
		Map<String, String> values = new HashMap<>();
		values.put("couponUrl", couponUrl);
		if (customerId != null) {
			return placeholderValues(sendLogMapper.findPlaceholderSource(customerId), couponUrl);
		}
		return values;
	}

	/** 실제 발송과 미리보기가 같은 치환 값을 쓰도록 공개한다 */
	public static Map<String, String> placeholderValues(CustomerPlaceholderSource source, String couponUrl) {
		Map<String, String> values = new HashMap<>();
		values.put("couponUrl", couponUrl);
		values.put("name", source.getName());
		values.put("email", source.getEmail());
		values.put("region", source.getRegionCode());
		values.put("totalPurchase",
			source.getTotalPurchase() == null ? null : String.valueOf(source.getTotalPurchase()));
		return values;
	}
}
