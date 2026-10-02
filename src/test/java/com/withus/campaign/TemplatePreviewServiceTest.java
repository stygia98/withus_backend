package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
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
		when(sendLogMapper.findPlaceholderSource(10L)).thenReturn(customer(null));

		TemplatePreviewResponse response = service.preview(1L, 10L, null);

		assertThat(response.subject()).startsWith("(광고)").contains("고객님께 드리는 선물");
		assertThat(response.html()).contains("고객님").contains("/unsubscribe/example");
		assertThat(response.smsBytes()).isNull();
		assertThat(response.defaultValueCount()).isNull();
	}

	@Test
	void 세그먼트가_있으면_기본값으로_나갈_인원을_센다() {
		template(Channel.EMAIL, "{{name|고객}}님", "본문", "N");
		when(sendLogMapper.findPlaceholderSource(10L)).thenReturn(customer("김민지"));
		when(segmentService.findTargetCustomers(7L)).thenReturn(List.of(1L, 2L, 3L));
		when(sendLogMapper.findPlaceholderSources(anyList()))
			.thenReturn(List.of(customer("김민지"), customer(null), customer("")));

		TemplatePreviewResponse response = service.preview(1L, 10L, 7L);

		assertThat(response.defaultValueCount().total()).isEqualTo(3);
		assertThat(response.defaultValueCount().usingDefault()).isEqualTo(2);
	}

	@Test
	void SMS는_치환_후_EUC_KR_바이트_수를_돌려준다() {
		template(Channel.SMS, null, "{{name|고객}}님 안녕", "N");
		when(sendLogMapper.findPlaceholderSource(10L)).thenReturn(customer("김민지"));

		TemplatePreviewResponse response = service.preview(1L, 10L, null);

		assertThat(response.subject()).isNull();
		assertThat(response.html()).isEqualTo("김민지님 안녕");
		assertThat(response.smsBytes()).isEqualTo(13); // 한글 6자×2 + 공백 1
	}
}
