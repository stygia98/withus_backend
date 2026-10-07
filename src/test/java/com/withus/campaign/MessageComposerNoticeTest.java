package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.withus.campaign.domain.SendKind;
import com.withus.campaign.domain.SendLog;
import com.withus.campaign.mapper.SendLogMapper;
import com.withus.campaign.service.AdCopyInserter;
import com.withus.campaign.service.ConsentNoticeCopy;
import com.withus.campaign.service.MessageComposer;
import com.withus.campaign.service.messaging.OutboundMessage;
import com.withus.common.domain.Channel;
import com.withus.common.render.DefaultPlaceholderRenderer;
import com.withus.coupon.service.CouponService;
import com.withus.customer.service.ConsentService;
import com.withus.tracking.service.TrackingLinkService;

/**
 * F-12 NOTICE 렌더링 분기 (PL 결정, 이슈 #81) — template·campaign_id 가 없어도 NPE 없이 채널별 본문이 만들어지고,
 * 쿠폰 발급·추적 치환을 하지 않으며, 동의 일시가 없으면 SKIPPED 로 종결하는지 확인한다. DB 없이 실행된다
 */
class MessageComposerNoticeTest {

	private static final OffsetDateTime CONSENT_AT = OffsetDateTime.parse("2024-10-07T09:00:00+09:00");

	private SendLogMapper sendLogMapper;
	private CouponService couponService;
	private TrackingLinkService trackingLinkService;
	private ConsentService consentService;
	private MessageComposer composer;

	@BeforeEach
	void setUp() {
		sendLogMapper = mock(SendLogMapper.class);
		couponService = mock(CouponService.class);
		trackingLinkService = mock(TrackingLinkService.class);
		consentService = mock(ConsentService.class);
		composer = new MessageComposer(sendLogMapper, couponService, new DefaultPlaceholderRenderer(),
			new AdCopyInserter("위드어스", "02-000-0000", "080-000-0000"), trackingLinkService, consentService,
			new ConsentNoticeCopy("위드어스", "080-000-0000"), "https://withus.local");
	}

	/** NOTICE 는 campaign_id·step_id·template_id 가 모두 없다 (ConsentNoticeBatch 적재 형태) */
	private static SendLog notice(Channel channel) {
		return SendLog.builder().sendLogId(100L).customerId(10L).channel(channel).kind(SendKind.NOTICE)
			.recipient(channel == Channel.EMAIL ? "customer@withus.local" : "01012345678").build();
	}

	@Test
	void 메일_NOTICE는_template과_campaign_id가_없어도_NPE_없이_고정_문구로_만들어진다() {
		when(consentService.findConsentAt(10L, Channel.EMAIL)).thenReturn(Optional.of(CONSENT_AT));

		Optional<OutboundMessage> result = composer.compose(notice(Channel.EMAIL), null,
			"https://withus.local/unsubscribe/abc", "https://withus.local/api/v1/unsubscribe/one-click/abc");

		assertThat(result).isPresent();
		OutboundMessage message = result.get();
		assertThat(message.channel()).isEqualTo(Channel.EMAIL);
		assertThat(message.to()).isEqualTo("customer@withus.local");
		assertThat(message.subject()).contains("위드어스").doesNotContain("(광고)");
		assertThat(message.body()).contains("2024년 10월 7일").contains("href=\"https://withus.local/unsubscribe/abc\"")
			.doesNotContain("(광고)");
		assertThat(message.headers()).containsEntry("List-Unsubscribe",
			"<https://withus.local/api/v1/unsubscribe/one-click/abc>");
	}

	@Test
	void SMS_NOTICE는_제목_없이_무료수신거부_번호를_담는다() {
		when(consentService.findConsentAt(10L, Channel.SMS)).thenReturn(Optional.of(CONSENT_AT));

		OutboundMessage message = composer.compose(notice(Channel.SMS), null, "unused", "unused").orElseThrow();

		assertThat(message.channel()).isEqualTo(Channel.SMS);
		assertThat(message.subject()).isNull();
		assertThat(message.body()).contains("2024년 10월 7일").contains("무료수신거부 080-000-0000").doesNotContain("(광고)");
		assertThat(message.headers()).isEmpty();
	}

	@Test
	void NOTICE는_쿠폰_조회와_발급_추적_치환을_하지_않는다() {
		when(consentService.findConsentAt(10L, Channel.EMAIL)).thenReturn(Optional.of(CONSENT_AT));

		OutboundMessage message = composer.compose(notice(Channel.EMAIL), null,
			"https://withus.local/unsubscribe/abc", "https://withus.local/one-click/abc").orElseThrow();

		// campaign_id 가 null 이라 long 파라미터 언박싱 NPE 가 나던 조회 자체를 하지 않는다
		verify(sendLogMapper, never()).findCouponIdByCampaignId(anyLong());
		verify(sendLogMapper, never()).findCouponIdByStepId(anyLong());
		verifyNoInteractions(couponService, trackingLinkService);
		// 수신거부 링크는 추적 치환되지 않은 원래 주소 그대로다(CLAUDE.md 6장 7번)
		assertThat(message.body()).contains("https://withus.local/unsubscribe/abc").doesNotContain("/t/");
	}

	@Test
	void 동의_일시가_없으면_NPE나_재시도가_아니라_SKIPPED로_종결한다() {
		when(consentService.findConsentAt(10L, Channel.EMAIL)).thenReturn(Optional.empty());

		Optional<OutboundMessage> result = composer.compose(notice(Channel.EMAIL), null, "u", "o");

		assertThat(result).isEmpty();
		verify(sendLogMapper).recordSkipped(100L, "CONSENT_DATE_MISSING");
		verify(sendLogMapper, never()).recordSkippedCoupon(anyLong());
		verify(trackingLinkService, never()).rewrite(any(), anyLong());
	}
}
