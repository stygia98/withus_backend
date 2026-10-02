package com.withus.workflow.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.withus.workflow.domain.WorkflowStep;

@Mapper
public interface WorkflowStepMapper {

	/** 캠페인의 전체 구조 조회 (step_id 순) */
	List<WorkflowStep> findByCampaignId(@Param("campaignId") long campaignId);

	/**
	 * 일괄 insert. next_step_id·yes_step_id·no_step_id 는 아직 모르므로(같은 호출 안에서 뒤에 나오는
	 * 노드를 가리킬 수 있다) 여기서 넣지 않는다 — insert 후 각 step 객체에 채워진 stepId로 updateLinks 를 부른다.
	 * useGeneratedKeys 로 생성된 키를 리스트 각 항목에 채워 받으려면 파라미터에 @Param 을 붙이면 안 된다
	 * (MyBatis Jdbc3KeyGenerator 가 "list" 별칭으로 컬렉션을 찾는데, @Param 을 쓰면 이 별칭이 안 생긴다).
	 * @return 실제로 insert 된 건수
	 */
	int insertBatch(List<WorkflowStep> steps);

	/** next·yes·no 연결 설정. 끝 노드거나 그 방향이 없으면 null */
	void updateLinks(@Param("stepId") long stepId, @Param("nextStepId") Long nextStepId,
		@Param("yesStepId") Long yesStepId, @Param("noStepId") Long noStepId);

	/** 여러 노드의 next·yes·no 를 UPDATE 한 번으로 설정한다. 각 step 의 stepId·nextStepId·yesStepId·noStepId 를 쓴다 */
	void updateLinksBatch(@Param("steps") List<WorkflowStep> steps);

	/** 캠페인 구조 저장(PUT)은 기존 구조를 통째로 지우고 다시 쓴다 */
	void deleteByCampaignId(@Param("campaignId") long campaignId);

	/** 캠페인 행을 FOR UPDATE 로 잠그고 상태를 돌려준다(campaign 테이블 SELECT). 구조 저장이 시작 요청·다른 저장과 겹치지 않게 한다 */
	String lockCampaignStatus(@Param("campaignId") long campaignId);
}
