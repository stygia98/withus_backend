-- 캠페인 시작 선점 표시 (이슈 #52, PR #54 리뷰)
-- 동시에 start() 를 부르는 요청 중 한 명만 적재·시작하도록 "누가 시작 중인가"를 updated_at 과 분리해 둔다.
-- NULL 이면 아무도 시작 중이 아니다. 시작이 끝나거나 실패하면 비우고, 프로세스가 죽어 남은 값은 서비스가 10분 뒤 낡은 선점으로 본다
ALTER TABLE campaign ADD COLUMN start_claimed_at TIMESTAMPTZ;
