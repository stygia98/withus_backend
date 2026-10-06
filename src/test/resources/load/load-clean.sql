-- load-data.sql 로 만든 부하 데이터만 지운다 (식별 태그: [LOAD] 이름, load-*@load.withus.local 이메일)
--   psql -h localhost -U <user> -d <db> -f src/test/resources/load/load-clean.sql
-- 부하 측정 중 생긴 부산물(발송 결과·추적 이벤트·쿠폰 발급·워크플로우 인스턴스)도 FK 순서에 맞춰 함께 지운다.
-- 여러 번 실행해도 안전하다(지울 게 없으면 0건).

\set ON_ERROR_STOP on
\timing on

BEGIN;

-- 부하 캠페인에 딸린 send_log (일회성·워크플로우 모두)
CREATE TEMP TABLE load_send_log ON COMMIT DROP AS
SELECT send_log_id FROM send_log
WHERE campaign_id IN (SELECT campaign_id FROM campaign WHERE name LIKE '[LOAD]%')
   OR customer_id IN (SELECT customer_id FROM customer WHERE email LIKE 'load-%@load.withus.local');

DELETE FROM track_event WHERE send_log_id IN (SELECT send_log_id FROM load_send_log);
DELETE FROM coupon_issue WHERE send_log_id IN (SELECT send_log_id FROM load_send_log)
   OR customer_id IN (SELECT customer_id FROM customer WHERE email LIKE 'load-%@load.withus.local');
DELETE FROM send_log WHERE send_log_id IN (SELECT send_log_id FROM load_send_log);

DELETE FROM workflow_instance
WHERE campaign_id IN (SELECT campaign_id FROM campaign WHERE name LIKE '[LOAD]%')
   OR customer_id IN (SELECT customer_id FROM customer WHERE email LIKE 'load-%@load.withus.local');
DELETE FROM workflow_step WHERE campaign_id IN (SELECT campaign_id FROM campaign WHERE name LIKE '[LOAD]%');

DELETE FROM consent_history WHERE customer_id IN (SELECT customer_id FROM customer WHERE email LIKE 'load-%@load.withus.local');
DELETE FROM purchase WHERE customer_id IN (SELECT customer_id FROM customer WHERE email LIKE 'load-%@load.withus.local');
DELETE FROM customer WHERE email LIKE 'load-%@load.withus.local';

DELETE FROM campaign WHERE name LIKE '[LOAD]%';
DELETE FROM template WHERE name LIKE '[LOAD]%';
DELETE FROM segment WHERE name LIKE '[LOAD]%';
DELETE FROM member WHERE email = 'load-owner@load.withus.local';

COMMIT;

ANALYZE customer;
ANALYZE send_log;

SELECT (SELECT count(*) FROM customer WHERE email LIKE 'load-%@load.withus.local') AS customers_left,
       (SELECT count(*) FROM campaign WHERE name LIKE '[LOAD]%') AS campaigns_left;
