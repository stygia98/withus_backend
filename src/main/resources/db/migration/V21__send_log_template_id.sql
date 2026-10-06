-- 테스트 발송(kind=TEST)은 campaign_id·step_id 가 없어 렌더링할 템플릿을 알 수 없다 (발송 큐 Plan 15장 결정 B)
-- 템플릿이 지워져도 이력은 남아야 하므로 ON DELETE SET NULL
ALTER TABLE send_log
    ADD COLUMN template_id BIGINT REFERENCES template (template_id) ON DELETE SET NULL;
