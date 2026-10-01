package com.withus.customer.controller;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.withus.common.response.ApiResponse;
import com.withus.common.response.PageResponse;
import com.withus.customer.dto.ConsentHistoryResponse;
import com.withus.customer.dto.ConsentUpdateRequest;
import com.withus.customer.dto.CustomerCreateRequest;
import com.withus.customer.dto.CustomerListItem;
import com.withus.customer.dto.CustomerResponse;
import com.withus.customer.dto.CustomerUpdateRequest;
import com.withus.customer.service.CustomerService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "고객", description = "고객 개별 관리 (API_SPEC 3장)")
@RestController
@RequestMapping("/api/v1/customers")
@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
@RequiredArgsConstructor
public class CustomerController {

	private final CustomerService customerService;

	@Operation(summary = "목록", description = "삭제되지 않은 고객. 이메일·휴대폰은 일부 마스킹. "
		+ "keyword 는 이름·이메일·휴대폰 부분 일치, region 은 시·도명 또는 코드, 동의·휴면 필터는 Y/N. "
		+ "sort 는 createdAt·name·joinedAt·totalPurchase 에 ,asc 또는 ,desc (기본 createdAt,desc). size 최대 100")
	@GetMapping
	public ApiResponse<PageResponse<CustomerListItem>> list(
		@RequestParam(required = false) String keyword,
		@RequestParam(required = false) String region,
		@RequestParam(required = false) String emailConsent,
		@RequestParam(required = false) String smsConsent,
		@RequestParam(required = false) String dormant,
		@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "20") int size,
		@Parameter(example = "joinedAt,desc") @RequestParam(required = false) String sort) {
		return ApiResponse.ok(
			customerService.list(keyword, region, emailConsent, smsConsent, dormant, page, size, sort));
	}

	@Operation(summary = "개별 등록", description = "이메일 소문자·trim, 휴대폰 숫자만, 지역명→코드로 저장. "
		+ "suppression 에 있는 채널은 동의 N 으로 저장하고 suppressedChannels 로 알린다. "
		+ "오류: CUSTOMER_DUPLICATE_EMAIL(409), CUSTOMER_INVALID_REGION/PHONE/DATE(400)")
	@PostMapping
	public ApiResponse<CustomerResponse> create(@Valid @RequestBody CustomerCreateRequest request) {
		return ApiResponse.ok(customerService.create(request));
	}

	@Operation(summary = "상세", description = "마스킹 없음. 삭제된 고객은 COMMON_NOT_FOUND(404)")
	@GetMapping("/{customerId}")
	public ApiResponse<CustomerResponse> get(@PathVariable long customerId) {
		return ApiResponse.ok(customerService.get(customerId));
	}

	@Operation(summary = "수정", description = "이름·이메일·휴대폰·지역·생년월일·가입일을 통째로 바꾼다(빈 값은 지움). "
		+ "누적구매액·수신동의는 바뀌지 않는다")
	@PatchMapping("/{customerId}")
	public ApiResponse<CustomerResponse> update(@PathVariable long customerId,
		@Valid @RequestBody CustomerUpdateRequest request) {
		return ApiResponse.ok(customerService.update(customerId, request));
	}

	@Operation(summary = "수신동의 변경", description = "채널별로 바꾸고 consent_history 에 기록한다. "
		+ "suppressedChannels 에 있는 채널을 Y로 바꾸려면 evidenceNote 필수(없으면 CUSTOMER_CONSENT_EVIDENCE_REQUIRED 422), "
		+ "이때 suppression 에서 해제된다. 같은 값이면 아무것도 바꾸지 않는다")
	@PatchMapping("/{customerId}/consent")
	public ApiResponse<CustomerResponse> changeConsent(@PathVariable long customerId,
		@Valid @RequestBody ConsentUpdateRequest request) {
		return ApiResponse.ok(customerService.changeConsent(customerId, request));
	}

	@Operation(summary = "동의 이력", description = "최신순. before 가 null 이면 최초 등록")
	@GetMapping("/{customerId}/consent-history")
	public ApiResponse<List<ConsentHistoryResponse>> consentHistory(@PathVariable long customerId) {
		return ApiResponse.ok(customerService.consentHistory(customerId));
	}

	@Operation(summary = "삭제", description = "논리 삭제. 같은 이메일로 다시 등록하면 새 고객이 된다")
	@DeleteMapping("/{customerId}")
	public ApiResponse<Void> delete(@PathVariable long customerId) {
		customerService.delete(customerId);
		return ApiResponse.ok(null);
	}
}
