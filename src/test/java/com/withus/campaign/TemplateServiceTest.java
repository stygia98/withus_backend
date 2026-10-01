package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.withus.campaign.domain.Template;
import com.withus.campaign.domain.TemplateErrorCode;
import com.withus.campaign.mapper.TemplateMapper;
import com.withus.campaign.service.TemplateService;
import com.withus.common.domain.Channel;
import com.withus.common.exception.BusinessException;

/** TemplateService 단위 테스트 (Mapper 는 목으로 대체, DB 없이 실행) */
@ExtendWith(MockitoExtension.class)
class TemplateServiceTest {

	@Mock
	TemplateMapper templateMapper;
	@InjectMocks
	TemplateService templateService;

	private Template email(String subject, String body) {
		Template template = new Template();
		template.setChannel(Channel.EMAIL);
		template.setName("템플릿");
		template.setSubject(subject);
		template.setBody(body);
		template.setAdYn("Y");
		return template;
	}

	@Test
	void EMAIL은_subject_없으면_거부된다() {
		Template template = email(null, "본문");

		assertThatThrownBy(() -> templateService.create(template, 1L))
			.isInstanceOf(BusinessException.class)
			.satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
				.isEqualTo(TemplateErrorCode.TEMPLATE_SUBJECT_REQUIRED));
	}

	@Test
	void 허용되지_않은_치환자는_거부된다() {
		Template template = email("제목", "연락처는 {{phone}} 입니다");

		assertThatThrownBy(() -> templateService.create(template, 1L))
			.isInstanceOf(BusinessException.class)
			.satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
				.isEqualTo(TemplateErrorCode.TEMPLATE_INVALID_PLACEHOLDER));
	}

	@Test
	void 기본값_문법의_치환자는_허용된다() {
		Template template = email("{{name|고객}}님께", "안녕하세요 {{name|고객}}님, 쿠폰: {{couponUrl}}");

		templateService.create(template, 1L);

		assertThat(template.getCreatedBy()).isEqualTo(1L);
	}

	@Test
	void 사용_중인_템플릿_수정은_409() {
		Template existing = email("제목", "본문");
		existing.setTemplateId(1L);
		when(templateMapper.findById(1L)).thenReturn(existing);
		when(templateMapper.existsInUseByStatus(1L)).thenReturn(true);

		assertThatThrownBy(() -> templateService.update(1L, email("새 제목", "새 본문")))
			.isInstanceOf(BusinessException.class)
			.satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
				.isEqualTo(TemplateErrorCode.TEMPLATE_IN_USE));
	}

	@Test
	void 참조중인_템플릿_삭제는_409() {
		Template existing = email("제목", "본문");
		existing.setTemplateId(1L);
		when(templateMapper.findById(1L)).thenReturn(existing);
		when(templateMapper.existsReferenced(1L)).thenReturn(true);

		assertThatThrownBy(() -> templateService.delete(1L))
			.isInstanceOf(BusinessException.class)
			.satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
				.isEqualTo(TemplateErrorCode.TEMPLATE_IN_USE));
	}

	@Test
	void 존재하지_않는_템플릿은_404() {
		when(templateMapper.findById(anyLong())).thenReturn(null);

		assertThatThrownBy(() -> templateService.getOrThrow(99L))
			.isInstanceOf(BusinessException.class)
			.satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
				.isEqualTo(TemplateErrorCode.TEMPLATE_NOT_FOUND));
	}

	@Test
	void 복제하면_이름에_접미사가_붙고_새로_insert된다() {
		Template source = email("제목", "본문");
		source.setTemplateId(1L);
		source.setName("원본");
		when(templateMapper.findById(1L)).thenReturn(source);

		Template copy = templateService.duplicate(1L, 2L);

		assertThat(copy.getName()).isEqualTo("원본 복사본");
		assertThat(copy.getCreatedBy()).isEqualTo(2L);
		assertThat(copy).isNotSameAs(source);
	}
}
