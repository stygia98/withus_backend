package com.withus.customer.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.withus.common.domain.Channel;
import com.withus.customer.domain.ConsentHistory;
import com.withus.customer.domain.Customer;
import com.withus.customer.domain.CustomerFields;
import com.withus.customer.domain.CustomerSearch;

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

	/** 동의 이력 기록. source: ADMIN, UPLOAD, UNSUBSCRIBE, BOUNCE, COMPLAINT */
	void insertConsentHistory(@Param("customerId") long customerId, @Param("channel") Channel channel,
		@Param("beforeYn") String beforeYn, @Param("afterYn") String afterYn, @Param("source") String source,
		@Param("note") String note);

	/** 삭제되지 않은 고객 목록 (검색·필터·정렬·페이징) */
	List<Customer> search(@Param("s") CustomerSearch search);

	long count(@Param("s") CustomerSearch search);

	/** 채널 동의 변경. Y면 동의 일시를 지금으로, N이면 비운다. 삭제된 고객이면 0 */
	int updateConsent(@Param("customerId") long customerId, @Param("channel") Channel channel,
		@Param("yn") String yn);

	void deleteSuppression(@Param("channel") Channel channel, @Param("value") String value);

	/** 최신순 */
	List<ConsentHistory> findConsentHistory(long customerId);

	/**
	 * 삭제되지 않았고, 채널 동의 Y 이고, 그 채널 값이 suppression 에 없으면 true. 쿼리 1회
	 * SMS 는 휴대폰이 비어 있으면 false (보낼 수 없는 고객 → 발송 큐에서 SKIPPED, PL 결정 #5)
	 */
	boolean isSendable(@Param("customerId") long customerId, @Param("channel") Channel channel);

	/** 휴면 조건에 맞는 고객을 휴면으로. 바뀐 건수 */
	int markDormant();

	/** 휴면 조건에서 벗어난 고객의 휴면 해제. 바뀐 건수 */
	int releaseDormant();
}
