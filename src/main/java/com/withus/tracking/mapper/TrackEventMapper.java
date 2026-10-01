package com.withus.tracking.mapper;

import java.time.OffsetDateTime;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.withus.tracking.domain.TrackEvent;
import com.withus.tracking.domain.TrackedSend;

@Mapper
public interface TrackEventMapper {

	/** 추적 토큰(UUID 문자열)으로 발송 건 조회. 없으면 null */
	TrackedSend findSendByToken(@Param("token") String token);

	/** 클릭 링크의 원본 URL. 없는 링크면 null */
	String findOriginalUrl(@Param("linkId") long linkId);

	/** 같은 템플릿에 등록된 추적 링크 수 (linkId 가 속한 템플릿 기준) */
	int countLinksOfSameTemplate(@Param("linkId") long linkId);

	/** since 이후 이 발송 건에서 클릭된 서로 다른 링크 수 (봇 포함 전체) */
	int countDistinctClickedLinksSince(@Param("sendLogId") long sendLogId, @Param("since") OffsetDateTime since);

	void insert(TrackEvent event);

	/** since 이후 이 발송 건의 이벤트를 봇으로 바꾼다. 바뀐 건수를 돌려준다 */
	int markBotSince(@Param("sendLogId") long sendLogId, @Param("since") OffsetDateTime since);

	/** 봇이 아닌 이벤트 존재 여부 (발송 종류 무관). 클릭 시 OPEN 보정 판단에만 쓴다 */
	boolean existsNotBotEvent(@Param("sendLogId") long sendLogId, @Param("eventType") String eventType);

	/** 봇이 아닌 이벤트 존재 여부. TEST·NOTICE 발송은 제외한다 (외부 공개용) */
	boolean existsHumanEvent(@Param("sendLogId") long sendLogId, @Param("eventType") String eventType);
}
