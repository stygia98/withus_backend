package com.withus.customer.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.withus.common.domain.Channel;
import com.withus.customer.domain.Customer;
import com.withus.customer.domain.CustomerFields;

@Mapper
public interface CustomerMapper {

	/** 삭제되지 않은 고객만 */
	Customer findActiveById(long customerId);

	/** 삭제되지 않은 고객 중 같은 이메일 존재 여부. excludeId 는 수정 시 자기 자신 제외 */
	boolean existsActiveEmail(@Param("email") String email, @Param("excludeId") Long excludeId);

	void insert(Customer customer);

	/** 이름·연락처·지역·날짜만 바꾼다 (누적구매액·수신동의 제외). 삭제된 고객이면 0 */
	int update(@Param("customerId") long customerId, @Param("f") CustomerFields fields);

	/** 논리 삭제. 이미 삭제됐거나 없으면 0 */
	int softDelete(long customerId);

	/** 이메일·휴대폰이 suppression 에 있는 채널 (정규화된 값으로 비교) */
	List<Channel> findSuppressedChannels(@Param("email") String email, @Param("phone") String phone);

	void insertConsentHistory(@Param("customerId") long customerId, @Param("channel") Channel channel,
		@Param("beforeYn") String beforeYn, @Param("afterYn") String afterYn, @Param("source") String source,
		@Param("note") String note);
}
