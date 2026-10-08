package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.withus.campaign.domain.CustomerPlaceholderSource;
import com.withus.campaign.domain.Template;
import com.withus.campaign.dto.TemplatePreviewResponse;
import com.withus.campaign.mapper.SendLogMapper;
import com.withus.campaign.service.AdCopyInserter;
import com.withus.campaign.service.TemplatePreviewService;
import com.withus.campaign.service.TemplateService;
import com.withus.common.domain.Channel;
import com.withus.common.exception.BusinessException;
import com.withus.common.render.DefaultPlaceholderRenderer;
import com.withus.segment.service.SegmentService;

/** 템플릿 미리보기 (미리보기 1/2) — 실제 렌더러·광고 문구 삽입기를 쓰고 DB 는 목으로 대신한다 */
class TemplatePreviewServiceTest {

	TemplateService templateService = mock(TemplateService.class);
	SendLogMapper sendLogMapper = mock(SendLogMapper.class);
	SegmentService segmentService = mock(SegmentService.class);
	TemplatePreviewService service = new TemplatePreviewService(templateService, sendLogMapper, segmentService,
		new DefaultPlaceholderRenderer(), new AdCopyInserter("위드어스", "02-000-0000", "080-000-0000"),
		"https://withus.local");

	private CustomerPlaceholderSource customer(String name) {
		CustomerPlaceholderSource source = new CustomerPlaceholderSource();
		set(source, "name", name);
		set(source, "email", "a@withus.local");
		set(source, "regionCode", "SEOUL");
		set(source, "totalPurchase", 0L);
		return source;
	}

	// @Getter 만 있는 프로젝션이라 리플렉션으로 채운다(MessageComposerTest 와 같은 방식)
	private static void set(CustomerPlaceholderSource source, String field, Object value) {
		try {
			var f = CustomerPlaceholderSource.class.getDeclaredField(field);
			f.setAccessible(true);
			f.set(source, value);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private Template template(Channel channel, String subject, String body, String adYn) {
		Template template = new Template();
		template.setChannel(channel);
		template.setSubject(subject);
		template.setBody(body);
		template.setAdYn(adYn);
		when(templateService.getOrThrow(1L)).thenReturn(template);
		return template;
	}

	@Test
	void 광고_메일은_제목에_광고가_붙고_이름_없는_고객은_기본값으로_보인다() {
		template(Channel.EMAIL, "{{name|고객}}님께 드리는 선물", "<p>{{name|고객}}님</p>", "Y");
		when(sendLogMapper.findPreviewSource(10L)).thenReturn(customer(null));

		TemplatePreviewResponse response = service.preview(1L, 10L, null, true);

		assertThat(response.subject()).startsWith("(광고)").contains("고객님께 드리는 선물");
		assertThat(response.html()).contains("고객님").contains("/unsubscribe/example");
		assertThat(response.smsBytes()).isNull();
		assertThat(response.defaultValueCount()).isNull();
	}

	@Test
	void 세그먼트가_있으면_기본값으로_나갈_인원을_센다() {
		template(Channel.EMAIL, "{{name|고객}}님", "본문", "N");
		when(sendLogMapper.findPreviewSource(10L)).thenReturn(customer("김민지"));
		when(segmentService.findTargetCustomers(7L)).thenReturn(List.of(1L, 2L, 3L));
		when(sendLogMapper.findPlaceholderSources(anyList()))
			.thenReturn(List.of(customer("김민지"), customer(null), customer("")));

		TemplatePreviewResponse response = service.preview(1L, 10L, 7L, true);

		assertThat(response.defaultValueCount().total()).isEqualTo(3);
		assertThat(response.defaultValueCount().usingDefault()).isEqualTo(2);
	}

	@Test
	void SMS는_치환_후_바이트_수와_SMS_LMS_판정을_돌려준다() {
		template(Channel.SMS, null, "{{name|고객}}님 안녕", "N");
		when(sendLogMapper.findPreviewSource(10L)).thenReturn(customer("김민지"));

		TemplatePreviewResponse response = service.preview(1L, 10L, null, true);

		assertThat(response.subject()).isNull();
		assertThat(response.html()).isNull();
		assertThat(response.text()).isEqualTo("김민지님 안녕");
		assertThat(response.smsBytes()).isEqualTo(13); // 한글 6자×2 + 공백 1
		assertThat(response.smsType()).isEqualTo("SMS");
	}

	@Test
	void 광고성_SMS_바이트에는_광고_발신자_수신거부_문구가_포함된다() {
		template(Channel.SMS, null, "안녕", "Y");
		when(sendLogMapper.findPreviewSource(10L)).thenReturn(customer("김민지"));

		TemplatePreviewResponse response = service.preview(1L, 10L, null, true);

		assertThat(response.text()).startsWith("(광고)위드어스 ").contains("안녕").endsWith("무료수신거부 080-000-0000");
		assertThat(response.smsBytes()).isEqualTo(TemplatePreviewService.smsBytes(response.text()));
		assertThat(response.smsBytes()).isGreaterThan(4);
	}

	@Test
	void SMS_LMS_경계는_90바이트다() {
		assertThat(TemplatePreviewService.smsBytes("가".repeat(45))).isEqualTo(90);
		assertThat(TemplatePreviewService.smsBytes("가".repeat(45) + "a")).isEqualTo(91);
	}

	@Test
	void 확장_한글과_이모지도_2바이트로_센다() {
		assertThat(TemplatePreviewService.smsBytes("똠뷁")).isEqualTo(4); // EUC-KR 에는 없는 글자
		assertThat(TemplatePreviewService.smsBytes("😀")).isEqualTo(2);
	}

	@Test
	void 이름의_HTML은_메일_미리보기에서_이스케이프된다() {
		template(Channel.EMAIL, "제목", "<p>{{name|고객}}님</p>", "N");
		when(sendLogMapper.findPreviewSource(10L)).thenReturn(customer("<img src=x onerror=alert(1)>"));

		TemplatePreviewResponse response = service.preview(1L, 10L, null, true);

		assertThat(response.html()).doesNotContain("<img").contains("&lt;img");
	}

	@Test
	void 세그먼트가_500건을_넘으면_500건씩_나눠_조회하고_total은_실제_센_행_수다() {
		template(Channel.EMAIL, "{{name|고객}}님", "본문", "N");
		when(sendLogMapper.findPreviewSource(10L)).thenReturn(customer("김민지"));
		List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 1001).boxed().toList();
		when(segmentService.findTargetCustomers(7L)).thenReturn(ids);
		// 청크마다 삭제 고객 1명이 빠져 있다고 가정: 500→499, 500→499, 1→0
		when(sendLogMapper.findPlaceholderSources(anyList())).thenAnswer(inv -> {
			int size = ((List<?>) inv.getArgument(0)).size();
			return java.util.Collections.nCopies(Math.max(size - 1, 0), customer(null));
		});

		TemplatePreviewResponse response = service.preview(1L, 10L, 7L, true);

		verify(sendLogMapper, times(3)).findPlaceholderSources(anyList());
		assertThat(response.defaultValueCount().total()).isEqualTo(998);
		assertThat(response.defaultValueCount().usingDefault()).isEqualTo(998);
	}

	@Test
	void 기본값_치환자가_없으면_고객_조회_없이_0명이다() {
		template(Channel.EMAIL, "{{name}}님", "본문 {{email}}", "N");
		when(sendLogMapper.findPreviewSource(10L)).thenReturn(customer("김민지"));
		when(segmentService.findTargetCustomers(7L)).thenReturn(List.of(1L, 2L, 3L));

		TemplatePreviewResponse response = service.preview(1L, 10L, 7L, true);

		verify(sendLogMapper, never()).findPlaceholderSources(anyList());
		assertThat(response.defaultValueCount().total()).isEqualTo(3);
		assertThat(response.defaultValueCount().usingDefault()).isZero();
	}

	@Test
	void STAFF는_sampleCustomerId를_보내도_고객을_조회하지_않고_고정_샘플을_쓴다() {
		template(Channel.EMAIL, "{{name|고객}}님", "<p>{{email}}</p>", "N");

		TemplatePreviewResponse response = service.preview(1L, 10L, null, false);

		verify(sendLogMapper, never()).findPreviewSource(anyLong());
		verify(sendLogMapper, never()).findPlaceholderSource(anyLong());
		assertThat(response.subject()).isEqualTo("홍길동님");
		assertThat(response.html()).contains("sample@example.com");
	}

	@Test
	void 샘플_고객이_없거나_삭제됐으면_404다() {
		template(Channel.EMAIL, "제목", "본문", "N");
		when(sendLogMapper.findPreviewSource(99L)).thenReturn(null);

		assertThatThrownBy(() -> service.preview(1L, 99L, null, true)).isInstanceOf(BusinessException.class);
	}
}
