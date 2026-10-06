package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.withus.campaign.domain.CustomerPlaceholderSource;
import com.withus.campaign.domain.SendKind;
import com.withus.campaign.domain.SendLog;
import com.withus.campaign.domain.Template;
import com.withus.campaign.mapper.SendLogMapper;
import com.withus.campaign.service.AdCopyInserter;
import com.withus.campaign.service.MessageComposer;
import com.withus.campaign.service.messaging.OutboundMessage;
import com.withus.common.domain.Channel;
import com.withus.common.render.DefaultPlaceholderRenderer;
import com.withus.coupon.service.CouponService;
import com.withus.tracking.service.TrackingLinkService;

/**
 * 발송 렌더링 조립 (발송 큐 Plan 9장) — 쿠폰·치환자·광고문구·추적·헤더 순서와 쿠폰 유효기간 밖 SKIPPED를 검증한다.
 * SendLogMapper·CouponService·TrackingLinkService 는 모의(mock) 로, PlaceholderRenderer·AdCopyInserter 는
 * 실제 구현(둘 다 DB 없는 순수 클래스)을 그대로 써서 렌더링 결과까지 확인한다
 */
class MessageComposerTest {

	private static final CustomerPlaceholderSource NAMED_CUSTOMER = source("홍길동");
	private static final CustomerPlaceholderSource NAMELESS_CUSTOMER = source(null);

	private SendLogMapper sendLogMapper;
	private CouponService couponService;
	private TrackingLinkService trackingLinkService;
	private MessageComposer messageComposer;

	@BeforeEach
	void setUp() {
		sendLogMapper = mock(SendLogMapper.class);
		couponService = mock(CouponService.class);
		trackingLinkService = mock(TrackingLinkService.class);
		when(trackingLinkService.rewrite(any(), anyLong())).thenAnswer(invocation -> invocation.getArgument(0));

		messageComposer = new MessageComposer(sendLogMapper, couponService, new DefaultPlaceholderRenderer(),
			new AdCopyInserter("위드어스", "02-000-0000", "080-000-0000"), trackingLinkService,
			"https://withus.local");
	}

	private static CustomerPlaceholderSource source(String name) {
		CustomerPlaceholderSource source = new CustomerPlaceholderSource();
		try {
			var nameField = CustomerPlaceholderSource.class.getDeclaredField("name");
			nameField.setAccessible(true);
			nameField.set(source, name);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
		return source;
	}

	private static SendLog emailLog() {
		return SendLog.builder().sendLogId(100L).campaignId(1L).customerId(10L).channel(Channel.EMAIL)
			.recipient("customer@withus.local").build();
	}

	private static Template template(String subject, String body, boolean ad) {
		Template template = new Template();
		template.setSubject(subject);
		template.setBody(body);
		template.setAdYn(ad ? "Y" : "N");
		return template;
	}

	@Test
	void 쿠폰_발급_치환_광고문구_추적_헤더_순서로_조립한다() {
		when(sendLogMapper.findCouponIdByCampaignId(1L)).thenReturn(5L);
		when(sendLogMapper.isCouponValid(5L)).thenReturn(Boolean.TRUE);
		UUID token = UUID.randomUUID();
		when(couponService.issue(5L, 10L, 100L)).thenReturn(token);
		when(sendLogMapper.findPlaceholderSource(10L)).thenReturn(NAMED_CUSTOMER);

		Optional<OutboundMessage> result = messageComposer.compose(
			emailLog(), template("{{name}}님 안내", "<p>{{name}}님, 쿠폰: {{couponUrl}}</p>", true),
			"https://withus.local/unsubscribe/abc", "https://withus.local/api/v1/unsubscribe/one-click/abc");

		assertThat(result).isPresent();
		OutboundMessage message = result.get();
		assertThat(message.subject()).isEqualTo("(광고) 홍길동님 안내");
		assertThat(message.body()).contains("홍길동님").contains("https://withus.local/c/" + token)
			.contains("수신거부");
		assertThat(message.headers())
			.containsEntry("List-Unsubscribe", "<https://withus.local/api/v1/unsubscribe/one-click/abc>")
			.containsEntry("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");

		InOrder order = inOrder(sendLogMapper, couponService, trackingLinkService);
		order.verify(sendLogMapper).findCouponIdByCampaignId(1L);
		order.verify(sendLogMapper).isCouponValid(5L);
		order.verify(couponService).issue(5L, 10L, 100L);
		order.verify(sendLogMapper).findPlaceholderSource(10L);
		order.verify(trackingLinkService).rewrite(any(), eq(100L));
	}

	@Test
	void TEST_발송은_쿠폰_발급과_추적_치환_없이_고정_샘플_값으로_조립한다() {
		SendLog testLog = SendLog.builder().sendLogId(200L).kind(SendKind.TEST).channel(Channel.EMAIL)
			.recipient("me@withus.local").templateId(7L).build();

		Optional<OutboundMessage> result = messageComposer.compose(
			testLog, template("{{name}}님 안내", "<p>{{name}}님, 쿠폰: {{couponUrl}}</p>", true),
			"https://withus.local/unsubscribe/example", "https://withus.local/api/v1/unsubscribe/one-click/example");

		assertThat(result).isPresent();
		OutboundMessage message = result.get();
		assertThat(message.to()).isEqualTo("me@withus.local");
		assertThat(message.subject()).isEqualTo("(광고) 홍길동님 안내");
		assertThat(message.body()).contains("홍길동님").contains("https://withus.local/c/example")
			.contains("https://withus.local/unsubscribe/example");
		// 쿠폰 조회·발급, 고객 조회, 추적 치환이 전혀 일어나지 않아야 한다(customer_id NULL 에 issue() 를 부르면 언박싱 NPE)
		verify(couponService, never()).issue(anyLong(), anyLong(), anyLong());
		verify(trackingLinkService, never()).rewrite(any(), anyLong());
		verify(sendLogMapper, never()).findCouponIdByCampaignId(anyLong());
		verify(sendLogMapper, never()).findPlaceholderSource(anyLong());
	}

	@Test
	void 쿠폰이_유효기간_밖이면_발급을_호출하지_않고_SKIPPED로_기록한다() {
		when(sendLogMapper.findCouponIdByCampaignId(1L)).thenReturn(5L);
		when(sendLogMapper.isCouponValid(5L)).thenReturn(Boolean.FALSE);

		Optional<OutboundMessage> result = messageComposer.compose(
			emailLog(), template("제목", "본문", false), "https://withus.local/unsubscribe/abc",
			"https://withus.local/api/v1/unsubscribe/one-click/abc");

		assertThat(result).isEmpty();
		verify(couponService, never()).issue(anyLong(), anyLong(), anyLong());
		verify(sendLogMapper).recordSkippedCoupon(100L);
	}

	@Test
	void 이름_없는_고객은_기본값_고객으로_치환된다() {
		when(sendLogMapper.findCouponIdByCampaignId(1L)).thenReturn(null);
		when(sendLogMapper.findPlaceholderSource(10L)).thenReturn(NAMELESS_CUSTOMER);

		Optional<OutboundMessage> result = messageComposer.compose(
			emailLog(), template("{{name}}님 환영", "본문", false), "https://withus.local/unsubscribe/abc",
			"https://withus.local/api/v1/unsubscribe/one-click/abc");

		assertThat(result).isPresent();
		assertThat(result.get().subject()).isEqualTo("고객님 환영");
		verify(couponService, never()).issue(anyLong(), anyLong(), anyLong());
	}

	@Test
	void SMS는_추적_치환과_헤더가_없다() {
		when(sendLogMapper.findCouponIdByCampaignId(1L)).thenReturn(null);
		when(sendLogMapper.findPlaceholderSource(10L)).thenReturn(NAMED_CUSTOMER);
		SendLog smsLog = SendLog.builder().sendLogId(100L).campaignId(1L).customerId(10L).channel(Channel.SMS)
			.recipient("01000000000").build();

		Optional<OutboundMessage> result = messageComposer.compose(
			smsLog, template(null, "{{name}}님 세일 중", true), "https://withus.local/unsubscribe/abc",
			"https://withus.local/api/v1/unsubscribe/one-click/abc");

		assertThat(result).isPresent();
		OutboundMessage message = result.get();
		assertThat(message.subject()).isNull();
		assertThat(message.body()).isEqualTo("(광고)위드어스 홍길동님 세일 중\n무료수신거부 080-000-0000");
		assertThat(message.headers()).isEmpty();
		verify(trackingLinkService, never()).rewrite(any(), anyLong());
	}
}
