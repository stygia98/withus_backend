package com.withus.customer.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.withus.common.response.ApiResponse;
import com.withus.customer.dto.CustomerCreateRequest;
import com.withus.customer.dto.CustomerResponse;
import com.withus.customer.dto.CustomerUpdateRequest;
import com.withus.customer.service.CustomerService;

import io.swagger.v3.oas.annotations.Operation;
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

	@Operation(summary = "삭제", description = "논리 삭제. 같은 이메일로 다시 등록하면 새 고객이 된다")
	@DeleteMapping("/{customerId}")
	public ApiResponse<Void> delete(@PathVariable long customerId) {
		customerService.delete(customerId);
		return ApiResponse.ok(null);
	}
}
