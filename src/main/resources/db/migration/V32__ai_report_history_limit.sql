-- AI-03 성과 요약 이력 정리 (팀원3). 이후 저장은 AiReportMapper.insertKeepingLatest 가 캠페인·유형별 최근 10건만 남긴다.
-- 1) 성공 발송이 없어 LLM 없이 저장하던 안내 문장(model = 'none')은 더 이상 저장하지 않으므로 지운다
DELETE FROM ai_report WHERE model = 'none';

-- 2) 캠페인·유형별로 최근 10건만 남긴다
DELETE FROM ai_report
WHERE report_id IN (
    SELECT report_id
    FROM (SELECT report_id,
                 row_number() OVER (PARTITION BY campaign_id, report_type ORDER BY report_id DESC) AS rn
          FROM ai_report) ranked
    WHERE rn > 10
);
