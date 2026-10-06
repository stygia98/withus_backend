-- 부하 측정용 데이터 생성 (W4 부하 1/3) — 고객 N명 + 그 고객 전원의 일회성 캠페인 send_log PENDING N건
--
-- Flyway 에 넣지 않는다. 로컬 DB 에서 사람이 직접 실행하는 스크립트다(운영 DB 금지).
--   psql -h localhost -U <user> -d <db> -v n=100000 -f src/test/resources/load/load-data.sql
-- 지울 때:  psql ... -f src/test/resources/load/load-clean.sql
--
-- 만드는 것(전부 식별 태그가 붙어 load-clean.sql 이 이것만 지운다)
--   member   load-owner@load.withus.local
--   customer load-{i}@load.withus.local   (이메일·SMS 동의 Y, 지역·나이·구매액을 섞어 세그먼트 조건에도 쓸 수 있다)
--   segment  [LOAD] 부하 대상 / template [LOAD] 부하 메일 / campaign [LOAD] 부하 캠페인
--   send_log kind=CAMPAIGN, priority 3, PENDING, channel EMAIL, 고객당 1건
--
-- 주의: 캠페인이 ACTIVE 라 SendDispatcher 가 켜져 있으면 곧바로 발송을 시작한다(local 프로필은 Mailpit·초당 1건).
--       적재 시간만 재려면 스케줄러를 끄고(withus.scheduler.send-dispatcher.enabled=false) 실행한다.

\set ON_ERROR_STOP on
\if :{?n}
\else
  \set n 100000
\endif
\timing on

BEGIN;

-- 이미 있으면 중복 적재하지 않는다
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM campaign WHERE name = '[LOAD] 부하 캠페인') THEN
        RAISE EXCEPTION '부하 데이터가 이미 있습니다. 먼저 load-clean.sql 을 실행하세요.';
    END IF;
END $$;

INSERT INTO member (email, password, name, role)
VALUES ('load-owner@load.withus.local', 'x', '부하 계정', 'MANAGER');

INSERT INTO segment (name, created_by)
SELECT '[LOAD] 부하 대상', member_id FROM member WHERE email = 'load-owner@load.withus.local';

INSERT INTO template (channel, name, subject, body, ad_yn, created_by)
SELECT 'EMAIL', '[LOAD] 부하 메일', '{{name|고객}}님 안내', '<p>{{name|고객}}님 부하 측정용 메일입니다.</p>', 'N', member_id
FROM member WHERE email = 'load-owner@load.withus.local';

INSERT INTO campaign (name, type, status, segment_id, template_id, started_at, created_by)
SELECT '[LOAD] 부하 캠페인', 'ONE_TIME', 'ACTIVE', s.segment_id, t.template_id, now(), m.member_id
FROM member m, segment s, template t
WHERE m.email = 'load-owner@load.withus.local' AND s.name = '[LOAD] 부하 대상' AND t.name = '[LOAD] 부하 메일';

INSERT INTO customer (name, email, phone, region_code, birth_date, joined_at, total_purchase,
                      email_consent_yn, email_consent_at, sms_consent_yn, sms_consent_at, source)
SELECT '부하고객' || i,
       'load-' || i || '@load.withus.local',
       '010' || lpad(i::text, 8, '0'),
       (ARRAY['SEOUL', 'GYEONGGI', 'BUSAN', 'INCHEON', 'DAEGU'])[i % 5 + 1],
       DATE '1960-01-01' + (i % 18000),
       CURRENT_DATE - (i % 700),
       (i % 50) * 10000,
       'Y', now(), 'Y', now(), 'UPLOAD'
FROM generate_series(1, :n) AS i;

-- customer_id 순서대로 넣어 send_log_id 가 고객 순서를 따르게 한다
INSERT INTO send_log (campaign_id, customer_id, recipient, channel, status, kind, priority)
SELECT (SELECT campaign_id FROM campaign WHERE name = '[LOAD] 부하 캠페인'),
       c.customer_id, c.email, 'EMAIL', 'PENDING', 'CAMPAIGN', 3
FROM customer c
WHERE c.email LIKE 'load-%@load.withus.local'
ORDER BY c.customer_id;

COMMIT;

-- 계획 수립이 실제 규모를 보도록 통계를 갱신한다
ANALYZE customer;
ANALYZE send_log;

SELECT (SELECT count(*) FROM customer WHERE email LIKE 'load-%@load.withus.local') AS customers,
       (SELECT count(*) FROM send_log s JOIN campaign c USING (campaign_id)
         WHERE c.name = '[LOAD] 부하 캠페인' AND s.status = 'PENDING') AS pending_send_logs,
       pg_size_pretty(pg_total_relation_size('send_log')) AS send_log_size;
