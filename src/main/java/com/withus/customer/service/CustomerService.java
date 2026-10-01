package com.withus.customer.service;

import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.withus.common.domain.Channel;
import com.withus.common.exception.BusinessException;
import com.withus.common.exception.CommonErrorCode;
import com.withus.customer.domain.Customer;
import com.withus.customer.domain.CustomerErrorCode;
import com.withus.customer.domain.CustomerFields;
import com.withus.customer.dto.CustomerCreateRequest;
import com.withus.customer.dto.CustomerResponse;
import com.withus.customer.dto.CustomerUpdateRequest;
import com.withus.customer.mapper.CustomerMapper;

import lombok.RequiredArgsConstructor;

/** 고객 개별 관리 (PRD F-02) */
@Service
@RequiredArgsConstructor
public class CustomerService {

	private final CustomerMapper customerMapper;

	/**
	 * 개별 등록. suppression 에 있는 채널은 요청과 관계없이 동의 N (F-01 규칙을 개별 등록에도 적용)
	 * 삭제된 고객과 같은 이메일이면 새 고객으로 만들고 이전 동의는 이어받지 않는다
	 */
	@Transactional
	public CustomerResponse create(CustomerCreateRequest req) {
		CustomerFields fields = normalize(req.name(), req.email(), req.phone(), req.region(), req.birthDate(),
			req.joinedAt());
		assertEmailAvailable(fields.email(), null);

		List<Channel> suppressed = customerMapper.findSuppressedChannels(fields.email(), fields.phone());
		String emailYn = consent(req.emailConsent(), suppressed.contains(Channel.EMAIL));
		String smsYn = consent(req.smsConsent(), suppressed.contains(Channel.SMS));

		Customer customer = Customer.manual(fields, emailYn, smsYn);
		try {
			customerMapper.insert(customer);
		} catch (DuplicateKeyException e) {
			// 동시 등록으로 부분 유니크 인덱스(uq_customer_email_active)에 걸린 경우
			throw new BusinessException(CustomerErrorCode.CUSTOMER_DUPLICATE_EMAIL);
		}
		long id = customer.getCustomerId();
		// 최초 동의도 이력으로 남긴다 (before_yn = NULL)
		if ("Y".equals(emailYn)) {
			customerMapper.insertConsentHistory(id, Channel.EMAIL, null, "Y", "ADMIN", null);
		}
		if ("Y".equals(smsYn)) {
			customerMapper.insertConsentHistory(id, Channel.SMS, null, "Y", "ADMIN", null);
		}
		// TODO(팀원2 연동, PL 확인): CUSTOMER_REGISTERED 워크플로우 트리거 (API_SPEC 3장, PRD 6장)
		return CustomerResponse.of(customerMapper.findActiveById(id), suppressed);
	}

	@Transactional(readOnly = true)
	public CustomerResponse get(long customerId) {
		Customer c = findActive(customerId);
		return CustomerResponse.of(c, customerMapper.findSuppressedChannels(c.getEmail(), c.getPhone()));
	}

	/** 이름·연락처·지역·날짜 수정. 누적구매액·수신동의는 바꾸지 않는다 */
	@Transactional
	public CustomerResponse update(long customerId, CustomerUpdateRequest req) {
		CustomerFields fields = normalize(req.name(), req.email(), req.phone(), req.region(), req.birthDate(),
			req.joinedAt());
		assertEmailAvailable(fields.email(), customerId);
		try {
			if (customerMapper.update(customerId, fields) == 0) {
				throw new BusinessException(CommonErrorCode.COMMON_NOT_FOUND);
			}
		} catch (DuplicateKeyException e) {
			throw new BusinessException(CustomerErrorCode.CUSTOMER_DUPLICATE_EMAIL);
		}
		return get(customerId);
	}

	/** 논리 삭제. suppression·동의 이력은 지우지 않는다 */
	@Transactional
	public void delete(long customerId) {
		if (customerMapper.softDelete(customerId) == 0) {
			throw new BusinessException(CommonErrorCode.COMMON_NOT_FOUND);
		}
		// TODO(팀원2 연동, PL 확인): 진행 중 워크플로우 인스턴스 CANCELLED (API_SPEC 3장)
	}

	private Customer findActive(long customerId) {
		Customer c = customerMapper.findActiveById(customerId);
		if (c == null) {
			throw new BusinessException(CommonErrorCode.COMMON_NOT_FOUND);
		}
		return c;
	}

	private void assertEmailAvailable(String email, Long excludeId) {
		if (customerMapper.existsActiveEmail(email, excludeId)) {
			throw new BusinessException(CustomerErrorCode.CUSTOMER_DUPLICATE_EMAIL);
		}
	}

	private static CustomerFields normalize(String name, String email, String phone, String region,
		String birthDate, String joinedAt) {
		String trimmedName = name == null || name.isBlank() ? null : name.trim();
		return new CustomerFields(trimmedName, CustomerNormalizer.email(email), CustomerNormalizer.phone(phone),
			CustomerNormalizer.regionCode(region), CustomerNormalizer.date(birthDate),
			CustomerNormalizer.date(joinedAt));
	}

	/** 요청 동의값(생략 시 N). suppression 에 있으면 N */
	private static String consent(String requested, boolean suppressed) {
		return !suppressed && "Y".equals(requested) ? "Y" : "N";
	}
}
