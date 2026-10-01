package com.withus.customer.service;

import java.util.List;
import java.util.Locale;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.withus.common.domain.Channel;
import com.withus.common.exception.BusinessException;
import com.withus.common.exception.CommonErrorCode;
import com.withus.common.response.PageResponse;
import com.withus.customer.domain.Customer;
import com.withus.customer.domain.CustomerErrorCode;
import com.withus.customer.domain.CustomerFields;
import com.withus.customer.domain.CustomerSearch;
import com.withus.customer.domain.CustomerSort;
import com.withus.customer.dto.ConsentHistoryResponse;
import com.withus.customer.dto.ConsentUpdateRequest;
import com.withus.customer.dto.CustomerCreateRequest;
import com.withus.customer.dto.CustomerListItem;
import com.withus.customer.dto.CustomerResponse;
import com.withus.customer.dto.CustomerUpdateRequest;
import com.withus.customer.mapper.CustomerMapper;

import lombok.RequiredArgsConstructor;

/** 고객 개별 관리 (PRD F-02) */
@Service
@RequiredArgsConstructor
public class CustomerService {

	private static final int MAX_PAGE_SIZE = 100;

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

	/**
	 * 목록 (API_SPEC 3장 GET /customers). sort 는 "키" 또는 "키,asc|desc" (기본 createdAt,desc)
	 * keyword 는 이름(대소문자 무시)·이메일(소문자)·휴대폰(숫자만) 부분 일치
	 */
	@Transactional(readOnly = true)
	public PageResponse<CustomerListItem> list(String keyword, String region, String emailConsent,
		String smsConsent, String dormant, int page, int size, String sort) {
		if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
			throw invalid("page 는 0 이상, size 는 1~" + MAX_PAGE_SIZE);
		}
		String[] sortParts = sort == null || sort.isBlank() ? new String[] { "createdAt" } : sort.split(",", -1);
		CustomerSort sortKey = CustomerSort.fromKey(sortParts[0].trim());
		String direction = sortParts.length > 1 ? sortParts[1].trim().toLowerCase(Locale.ROOT) : "desc";
		if (sortKey == null || sortParts.length > 2 || !(direction.equals("asc") || direction.equals("desc"))) {
			throw invalid("sort 는 createdAt·name·joinedAt·totalPurchase 와 asc·desc 만 쓸 수 있습니다.");
		}

		String kw = keyword == null || keyword.isBlank() ? null : keyword.trim();
		String digits = kw == null ? "" : kw.replaceAll("\\D", "");
		CustomerSearch search = new CustomerSearch(like(kw), like(CustomerNormalizer.email(kw)),
			digits.isEmpty() ? null : like(digits), CustomerNormalizer.regionCode(region), yn(emailConsent),
			yn(smsConsent), yn(dormant), sortKey, direction.equals("desc"), size, (long) page * size);

		List<CustomerListItem> content = customerMapper.search(search).stream().map(CustomerListItem::of).toList();
		return PageResponse.of(content, page, size, customerMapper.count(search));
	}

	/**
	 * 수신동의 변경 (PRD 7장). suppression 에 있는 채널을 Y로 바꾸려면 증빙 메모가 필요하고,
	 * 그때 suppression 에서 지우고 이력에 메모를 남긴다 (CLAUDE.md 6장 10번)
	 */
	@Transactional
	public CustomerResponse changeConsent(long customerId, ConsentUpdateRequest req) {
		Customer c = findActive(customerId);
		Channel channel = req.channel();
		String before = channel == Channel.EMAIL ? c.getEmailConsentYn() : c.getSmsConsentYn();
		String after = req.consent();
		if (after.equals(before)) {
			return get(customerId);
		}

		String value = channel == Channel.EMAIL ? c.getEmail() : c.getPhone();
		boolean suppressed = customerMapper.findSuppressedChannels(c.getEmail(), c.getPhone()).contains(channel);
		String note = req.evidenceNote() == null || req.evidenceNote().isBlank() ? null : req.evidenceNote().trim();
		if ("Y".equals(after) && suppressed) {
			if (note == null) {
				throw new BusinessException(CustomerErrorCode.CUSTOMER_CONSENT_EVIDENCE_REQUIRED);
			}
			customerMapper.deleteSuppression(channel, value);
		}
		customerMapper.updateConsent(customerId, channel, after);
		customerMapper.insertConsentHistory(customerId, channel, before, after, "ADMIN", note);
		return get(customerId);
	}

	@Transactional(readOnly = true)
	public List<ConsentHistoryResponse> consentHistory(long customerId) {
		findActive(customerId);
		return customerMapper.findConsentHistory(customerId).stream().map(ConsentHistoryResponse::of).toList();
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

	/** 부분 일치 LIKE 패턴. 검색어의 \ % _ 는 문자 그대로 찾는다 (PostgreSQL 기본 이스케이프 \) */
	private static String like(String value) {
		return value == null ? null : "%" + value.replaceAll("([\\\\%_])", "\\\\$1") + "%";
	}

	/** Y/N 필터. 비어 있으면 조건 없음 */
	private static String yn(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		if (!value.equals("Y") && !value.equals("N")) {
			throw invalid("동의·휴면 필터는 Y 또는 N");
		}
		return value;
	}

	private static BusinessException invalid(String message) {
		return new BusinessException(CommonErrorCode.COMMON_INVALID_INPUT, message, null);
	}
}
