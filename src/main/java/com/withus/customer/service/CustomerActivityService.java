package com.withus.customer.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.withus.common.exception.BusinessException;
import com.withus.common.exception.CommonErrorCode;
import com.withus.customer.dto.CustomerActivityResponse;
import com.withus.customer.mapper.CustomerActivityMapper;
import com.withus.customer.mapper.CustomerMapper;

import lombok.RequiredArgsConstructor;

/** 고객 상세의 발송·이벤트·쿠폰 이력 (PRD 4장 /customers/[id]) */
@Service
@RequiredArgsConstructor
public class CustomerActivityService {

	private final CustomerMapper customerMapper;
	private final CustomerActivityMapper activityMapper;

	@Transactional(readOnly = true)
	public CustomerActivityResponse get(long customerId) {
		if (customerMapper.findActiveById(customerId) == null) {
			throw new BusinessException(CommonErrorCode.COMMON_NOT_FOUND);
		}
		return new CustomerActivityResponse(activityMapper.findSends(customerId), activityMapper.findCoupons(customerId));
	}
}
