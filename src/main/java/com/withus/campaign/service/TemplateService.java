package com.withus.campaign.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.withus.campaign.domain.Template;
import com.withus.campaign.domain.TemplateErrorCode;
import com.withus.campaign.mapper.TemplateMapper;
import com.withus.common.domain.Channel;
import com.withus.common.exception.BusinessException;
import com.withus.common.exception.CommonErrorCode;
import com.withus.common.response.PageResponse;

/**
 * 템플릿 생성·수정·삭제·복제와 입력 검증 (API_SPEC 5장)
 * 치환은 이 클래스의 책임이 아니다 — 여기서는 허용된 치환자인지만 검증하고,
 * 실제 치환은 발송 직전 common.render.PlaceholderRenderer(팀원3)가 한다
 */
@Service
public class TemplateService {

	/** {{key}} 또는 {{key|기본값}}. key 는 영문·숫자·언더스코어만 허용 */
	private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)(?:\\|[^}]*)?\\s*}}");

	/** PRD F-04: 템플릿에서 쓸 수 있는 치환자 전체 목록 */
	private static final Set<String> ALLOWED_PLACEHOLDERS = Set.of(
		"name", "email", "region", "totalPurchase", "couponUrl");

	/** 고객 목록(CustomerService)과 같은 상한 */
	private static final int MAX_PAGE_SIZE = 100;

	private final TemplateMapper templateMapper;

	public TemplateService(TemplateMapper templateMapper) {
		this.templateMapper = templateMapper;
	}

	public PageResponse<Template> list(Channel channel, int page, int size) {
		if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
			throw new BusinessException(CommonErrorCode.COMMON_INVALID_INPUT,
				"page 는 0 이상, size 는 1~" + MAX_PAGE_SIZE, null);
		}
		String channelValue = channel == null ? null : channel.name();
		List<Template> content = templateMapper.findList(channelValue, page * size, size);
		long total = templateMapper.count(channelValue);
		return PageResponse.of(content, page, size, total);
	}

	/** 상세 화면의 inUse 표시용 (수정 가능 여부와 같은 기준) */
	public boolean isInUse(long templateId) {
		return templateMapper.existsInUseByStatus(templateId);
	}

	public Template create(Template template, long memberId) {
		validate(template);
		template.setCreatedBy(memberId);
		templateMapper.insert(template);
		return template;
	}

	public Template update(long templateId, Template changes) {
		Template existing = getOrThrow(templateId);
		if (templateMapper.existsInUseByStatus(templateId)) {
			throw new BusinessException(TemplateErrorCode.TEMPLATE_IN_USE);
		}
		existing.setName(changes.getName());
		existing.setSubject(changes.getSubject());
		existing.setBody(changes.getBody());
		existing.setAdYn(changes.getAdYn());
		validate(existing);
		templateMapper.update(existing);
		return existing;
	}

	public void delete(long templateId) {
		getOrThrow(templateId);
		if (templateMapper.existsReferenced(templateId)) {
			throw new BusinessException(TemplateErrorCode.TEMPLATE_IN_USE);
		}
		templateMapper.delete(templateId);
	}

	/** 이름에 접미사를 붙여 새 템플릿으로 복제한다. 사용 중 여부와 무관하게 항상 허용 */
	public Template duplicate(long templateId, long memberId) {
		Template source = getOrThrow(templateId);
		Template copy = new Template();
		copy.setChannel(source.getChannel());
		copy.setName(source.getName() + " 복사본");
		copy.setSubject(source.getSubject());
		copy.setBody(source.getBody());
		copy.setAdYn(source.getAdYn());
		copy.setCreatedBy(memberId);
		templateMapper.insert(copy);
		return copy;
	}

	public Template getOrThrow(long templateId) {
		Template template = templateMapper.findById(templateId);
		if (template == null) {
			throw new BusinessException(TemplateErrorCode.TEMPLATE_NOT_FOUND);
		}
		return template;
	}

	private void validate(Template template) {
		if (template.getChannel() == Channel.EMAIL
			&& (template.getSubject() == null || template.getSubject().isBlank())) {
			throw new BusinessException(TemplateErrorCode.TEMPLATE_SUBJECT_REQUIRED);
		}
		validatePlaceholders(template.getSubject());
		validatePlaceholders(template.getBody());
	}

	private void validatePlaceholders(String text) {
		if (text == null) {
			return;
		}
		Matcher matcher = PLACEHOLDER.matcher(text);
		while (matcher.find()) {
			String key = matcher.group(1);
			if (!ALLOWED_PLACEHOLDERS.contains(key)) {
				throw new BusinessException(TemplateErrorCode.TEMPLATE_INVALID_PLACEHOLDER,
					TemplateErrorCode.TEMPLATE_INVALID_PLACEHOLDER.message(), Map.of("placeholder", key));
			}
		}
	}
}
