package com.withus.customer.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;

import com.withus.customer.domain.CouponActivity;
import com.withus.customer.domain.SendActivity;

/** 다른 구간 테이블(send_log·campaign·track_event·coupon_issue·coupon)은 조회만 한다 */
@Mapper
public interface CustomerActivityMapper {

	/** 최근 100건, 최신순 */
	List<SendActivity> findSends(long customerId);

	/** 최신 발급순 */
	List<CouponActivity> findCoupons(long customerId);
}
