package com.withus.campaign.service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.withus.campaign.domain.CustomerPlaceholderSource;
import com.withus.campaign.domain.SendLog;
import com.withus.campaign.domain.Template;
import com.withus.campaign.mapper.SendLogMapper;
import com.withus.campaign.service.messaging.OutboundMessage;
import com.withus.common.domain.Channel;
import com.withus.common.render.PlaceholderRenderer;
import com.withus.coupon.service.CouponService;
import com.withus.tracking.service.TrackingLinkService;

/**
 * 재확인(SendRecheck)을 통과한 건만 렌더링한다(발송 큐 Plan 9장). 순서: 쿠폰 발급(연결된 경우만, 유효기간 밖이면
 * SKIPPED COUPON_INVALID) → 치환자 치환 → 광고 문구 삽입 → (EMAIL) 추적 치환 → (EMAIL) List-Unsubscribe 헤더.
 */
@Component
public class MessageComposer {

	private final SendLogMapper sendLogMapper;
	private final CouponService couponService;
	private final PlaceholderRenderer placeholderRenderer;
	private final AdCopyInserter adCopyInserter;
	private final TrackingLinkService trackingLinkService;
	private final String trackingBaseUrl;

	public MessageComposer(SendLogMapper sendLogMapper, CouponService couponService,
			PlaceholderRenderer placeholderRenderer, AdCopyInserter adCopyInserter,
			TrackingLinkService trackingLinkService, @Value("${withus.tracking.base-url}") String trackingBaseUrl) {
		this.sendLogMapper = sendLogMapper;
		this.couponService = couponService;
		this.placeholderRenderer = placeholderRenderer;
		this.adCopyInserter = adCopyInserter;
		this.trackingLinkService = trackingLinkService;
		this.trackingBaseUrl = trackingBaseUrl;
	}

	/**
	 * @param unsubscribeUrl         본문 링크 — 확인 화면 /unsubscribe/{token}
	 * @param unsubscribeOneClickUrl List-Unsubscribe 헤더 전용 — 메일 앱이 바로 POST 하는
	 *                                /api/v1/unsubscribe/one-click/{token}(RFC 8058, Plan 15장 B3)
	 * @return 렌더링된 메시지. 비어 있으면 쿠폰이 유효기간 밖이라 SKIPPED(COUPON_INVALID)로 이미 기록했다는 뜻
	 */
	public Optional<OutboundMessage> compose(SendLog sendLog, Template template, String unsubscribeUrl,
			String unsubscribeOneClickUrl) {
		Long couponId = sendLog.getStepId() != null
			? sendLogMapper.findCouponIdByStepId(sendLog.getStepId())
			: sendLogMapper.findCouponIdByCampaignId(sendLog.getCampaignId());

		String couponUrl = "";
		if (couponId != null) {
			if (!Boolean.TRUE.equals(sendLogMapper.isCouponValid(couponId))) {
				sendLogMapper.recordSkippedCoupon(sendLog.getSendLogId());
				return Optional.empty();
			}
			UUID token = couponService.issue(couponId, sendLog.getCustomerId(), sendLog.getSendLogId());
			couponUrl = trackingBaseUrl + "/c/" + token;
		}

		Map<String, String> values = placeholderValues(sendLog.getCustomerId(), couponUrl);
		boolean isAd = template.isAd();

		if (sendLog.getChannel() == Channel.EMAIL) {
			String subject = adCopyInserter.insertSubject(placeholderRenderer.render(template.getSubject(), values),
				isAd);
			String rendered = placeholderRenderer.renderHtml(template.getBody(), values);
			String withAd = adCopyInserter.insertEmailBody(rendered, isAd, unsubscribeUrl);
			String body = trackingLinkService.rewrite(withAd, sendLog.getSendLogId());
			Map<String, String> headers = Map.of(
				"List-Unsubscribe", "<" + unsubscribeOneClickUrl + ">",
				"List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
			return Optional.of(new OutboundMessage(Channel.EMAIL, sendLog.getRecipient(), subject, body, headers));
		}

		String rendered = placeholderRenderer.render(template.getBody(), values);
		String body = adCopyInserter.insertSms(rendered, isAd);
		return Optional.of(new OutboundMessage(Channel.SMS, sendLog.getRecipient(), null, body, Map.of()));
	}

	private Map<String, String> placeholderValues(Long customerId, String couponUrl) {
		Map<String, String> values = new HashMap<>();
		values.put("couponUrl", couponUrl);
		if (customerId != null) {
			CustomerPlaceholderSource source = sendLogMapper.findPlaceholderSource(customerId);
			values.put("name", source.getName());
			values.put("email", source.getEmail());
			values.put("region", source.getRegionCode());
			values.put("totalPurchase",
				source.getTotalPurchase() == null ? null : String.valueOf(source.getTotalPurchase()));
		}
		return values;
	}
}
