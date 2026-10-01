package com.withus.auth.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.withus.auth.domain.Member;
import com.withus.auth.domain.Role;

@Mapper
public interface MemberMapper {

	Member findByEmail(String email);

	Member findById(long memberId);

	Member findByRefreshTokenHash(String refreshTokenHash);

	boolean existsOwner();

	void insert(Member member);

	/** 실패 1회 기록. 5회째에 5분 잠그고 카운트를 0으로 되돌린다 (한 문장으로 처리해 동시 요청에도 안전) */
	void recordLoginFailure(long memberId);

	/** 로그인 성공: 실패 기록을 지우고 새 Refresh 토큰 해시를 저장한다 (사용자당 세션 1개) */
	void recordLoginSuccess(@Param("memberId") long memberId, @Param("refreshTokenHash") String refreshTokenHash);

	/** Refresh 토큰 교체. 이전 해시가 일치할 때만 바뀌므로 동시 재발급 중 하나만 성공한다 */
	int rotateRefreshToken(@Param("memberId") long memberId, @Param("oldHash") String oldHash,
		@Param("newHash") String newHash);

	void clearRefreshToken(long memberId);

	/** 사용자 관리 목록 (member_id 순) */
	List<Member> findAll();

	boolean existsEmail(String email);

	/** 역할·활성 여부 변경. null 이면 그대로 둔다. 다시 로그인하도록 Refresh 토큰도 지운다 */
	int updateRoleAndActive(@Param("memberId") long memberId, @Param("role") Role role,
		@Param("activeYn") String activeYn);
}
