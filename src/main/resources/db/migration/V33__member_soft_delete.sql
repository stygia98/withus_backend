-- 사용자 계정 삭제 표시 (PL · auth). 계정은 campaign·segment·template·purchase 의 created_by 로 참조돼 실제로 지우지 않는다.
-- 삭제하면 deleted_at 만 기록하고 이름·이메일·비밀번호·역할은 남긴다 → 사용자 관리 화면에서 복구할 수 있다.
ALTER TABLE member ADD COLUMN deleted_at TIMESTAMPTZ;

-- 이메일은 삭제되지 않은 계정끼리만 유일하다. 삭제한 계정의 이메일로 새 계정을 만들 수 있고,
-- 같은 이메일의 계정이 다시 생겼으면 복구는 이 인덱스가 막는다(MEMBER_DUPLICATE_EMAIL)
ALTER TABLE member DROP CONSTRAINT member_email_key;
CREATE UNIQUE INDEX uq_member_email_active ON member (email) WHERE deleted_at IS NULL;
