package com.withus.campaign.service;

import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.withus.campaign.domain.Template;
import com.withus.common.domain.Channel;
import com.withus.common.exception.BusinessException;
import com.withus.common.exception.CommonErrorCode;
import com.withus.common.normalize.Emails;
import com.withus.customer.service.CustomerNormalizer;

/**
 * 템플릿 테스트 발송 (API_SPEC 5장, PRD F-04). 수신처 1건을 공통 발송 큐에 kind=TEST 로 적재할 뿐이고, 실제 발송·렌더링은
 * 다른 발송과 같은 SendDispatcher 가 한다. 수신동의·시간창·통계는 TEST 라 적용하지 않는다(CLAUDE.md 6장 3·8번)
 */
@Service
public class TemplateTestSendService {

	private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

	private final TemplateService templateService;
	private final SendQueueService sendQueueService;

	public TemplateTestSendService(TemplateService templateService, SendQueueService sendQueueService) {
		this.templateService = templateService;
		this.sendQueueService = sendQueueService;
	}

	public void testSend(long templateId, String recipient) {
		Template template = templateService.getOrThrow(templateId);
		sendQueueService.enqueueTest(templateId, template.getChannel(), normalize(template.getChannel(), recipient));
	}

	/** 이메일은 소문자+trim, 휴대폰은 숫자만(CLAUDE.md 6장 9번). 형식이 틀리면 큐에 쌓이기 전에 400 */
	private static String normalize(Channel channel, String recipient) {
		if (channel == Channel.EMAIL) {
			String email = Emails.normalize(recipient);
			if (!EMAIL.matcher(email).matches()) {
				throw new BusinessException(CommonErrorCode.COMMON_INVALID_INPUT, "이메일 형식이 올바르지 않습니다.", null);
			}
			return email;
		}
		return CustomerNormalizer.phone(recipient);
	}
}
