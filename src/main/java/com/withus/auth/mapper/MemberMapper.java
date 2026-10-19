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

	/** 사용자 관리 목록 (member_id 순, 삭제한 계정 제외) */
	List<Member> findAll();

	/** 삭제한 계정 목록 (최근 삭제순) — 복구용 */
	List<Member> findDeleted();

	/** 삭제되지 않은 계정 중 같은 이메일이 있는가. exceptMemberId 가 있으면 그 계정은 뺀다(수정할 때 자기 자신) */
	boolean existsEmail(@Param("email") String email, @Param("exceptMemberId") Long exceptMemberId);

	/**
	 * 이름·이메일·역할·활성 여부 변경. null 이면 그대로 둔다. 역할·활성 여부가 바뀌면 다시 로그인하도록 Refresh 토큰도 지운다.
	 * 삭제한 계정은 바꾸지 않는다(0건)
	 */
	int updateMember(@Param("memberId") long memberId, @Param("name") String name, @Param("email") String email,
		@Param("role") Role role, @Param("activeYn") String activeYn);

	/** 삭제 표시 (로그인 끊기). 이미 삭제됐거나 없으면 0건 */
	int markDeleted(long memberId);

	/** 삭제 표시를 지운다. 삭제된 계정이 아니면 0건, 같은 이메일의 계정이 있으면 DuplicateKeyException */
	int restore(long memberId);

	/** 비밀번호 해시 교체. 기존 로그인을 끊고(Refresh 삭제) 실패 기록·잠금도 풀어 새 비밀번호로 바로 들어오게 한다 */
	int updatePassword(@Param("memberId") long memberId, @Param("password") String password);
}
