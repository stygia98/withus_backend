package com.withus.coupon.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.withus.common.exception.BusinessException;
import com.withus.common.exception.CommonErrorCode;
import com.withus.common.response.PageResponse;
import com.withus.coupon.domain.Coupon;
import com.withus.coupon.domain.CouponDraft;
import com.withus.coupon.domain.CouponErrorCode;
import com.withus.coupon.domain.CouponIssueRow;
import com.withus.coupon.domain.DiscountType;
import com.withus.coupon.mapper.CouponMapper;

/** 관리자 쿠폰 정의·발급 현황 (API_SPEC 7장, PRD F-10) */
@Service
public class CouponAdminService {

	private static final int MAX_PAGE_SIZE = 100;

	private final CouponMapper couponMapper;
	private Clock clock = Clock.system(ZoneId.of("Asia/Seoul"));

	public CouponAdminService(CouponMapper couponMapper) {
		this.couponMapper = couponMapper;
	}

	void setClock(Clock clock) {
		this.clock = clock;
	}

	public LocalDate today() {
		return LocalDate.now(clock);
	}

	public PageResponse<Coupon> list(int page, int size) {
		checkPage(page, size);
		return PageResponse.of(couponMapper.findList(page * size, size), page, size, couponMapper.count());
	}

	public Coupon get(long couponId) {
		Coupon coupon = couponMapper.findById(couponId);
		if (coupon == null) {
			throw new BusinessException(CouponErrorCode.COUPON_NOT_FOUND);
		}
		return coupon;
	}

	@Transactional
	public Coupon create(CouponDraft draft) {
		long couponId = couponMapper.insert(normalize(draft));
		return get(couponId);
	}

	/**
	 * 발급 이력이 없으면 전부 바꿀 수 있다. 있으면 종료일을 늦추는 것만 허용한다 —
	 * 이미 받은 고객의 할인 내용·기간이 줄어들면 안 되기 때문이다 (API_SPEC 7장).
	 */
	@Transactional
	public Coupon update(long couponId, CouponDraft request) {
		if (couponMapper.lockById(couponId) == null) {
			throw new BusinessException(CouponErrorCode.COUPON_NOT_FOUND);
		}
		CouponDraft draft = normalize(request);
		if (couponMapper.countIssues(couponId) > 0 && !isExtensionOnly(get(couponId), draft)) {
			throw new BusinessException(CouponErrorCode.COUPON_ALREADY_ISSUED);
		}
		couponMapper.update(couponId, draft);
		return get(couponId);
	}

	public PageResponse<CouponIssueRow> issues(long couponId, int page, int size) {
		checkPage(page, size);
		get(couponId);
		return PageResponse.of(couponMapper.findIssuesByCoupon(couponId, page * size, size), page, size,
			couponMapper.countIssuesByCoupon(couponId));
	}

	/** 구매 등록 화면용. usable 이면 오늘 사용 가능한 것만 */
	public List<CouponIssueRow> customerIssues(long customerId, boolean usable) {
		return couponMapper.findIssuesByCustomer(customerId, usable ? today() : null);
	}

	/** DB CHECK 제약보다 먼저 API 오류 코드로 알려 준다 */
	static CouponDraft normalize(CouponDraft d) {
		if (d.validTo().isBefore(d.validFrom())) {
			throw new BusinessException(CouponErrorCode.COUPON_INVALID_PERIOD);
		}
		if (d.discountType() == DiscountType.RATE) {
			if (d.discountValue() > 100) {
				throw new BusinessException(CommonErrorCode.COMMON_INVALID_INPUT, "정률 할인은 100% 이하입니다.", null);
			}
			if (d.maxDiscountAmount() == null) {
				throw new BusinessException(CouponErrorCode.COUPON_RATE_CAP_REQUIRED);
			}
		}
		// 정액은 상한이 의미가 없으므로 저장하지 않는다
		Integer cap = d.discountType() == DiscountType.RATE ? d.maxDiscountAmount() : null;
		return new CouponDraft(d.name().strip(), d.discountType(), d.discountValue(), cap, d.validFrom(), d.validTo());
	}

	private static boolean isExtensionOnly(Coupon current, CouponDraft d) {
		return Objects.equals(current.getName(), d.name())
			&& current.getDiscountType() == d.discountType()
			&& Objects.equals(current.getDiscountValue(), d.discountValue())
			&& Objects.equals(current.getMaxDiscountAmount(), d.maxDiscountAmount())
			&& current.getValidFrom().equals(d.validFrom())
			&& !d.validTo().isBefore(current.getValidTo());
	}

	private static void checkPage(int page, int size) {
		if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
			throw new BusinessException(CommonErrorCode.COMMON_INVALID_INPUT,
				"page 는 0 이상, size 는 1~" + MAX_PAGE_SIZE, null);
		}
	}
}
