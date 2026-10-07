-- local 프로필 전용 시연용 발송·추적·쿠폰 데이터 (DB_SCHEMA.md 9장, project #12 PL 결정 ②안). 운영 DB 에는 들어가지 않는다.
-- 파일명: Flyway 는 반복 마이그레이션(R__)을 설명의 사전순으로 실행한다. 'seed local demo' 는 'seed local' 뒤라
--         R__seed_local.sql(고객·세그먼트·템플릿·쿠폰)이 먼저 들어간 다음 실행된다 (FlywayLocationTest 가 순서를 지킨다).
-- 목적: 대시보드(KPI·일별 발송·최근 이벤트·활성 캠페인)와 캠페인 성과(퍼널·단계별·전환율·기간 필터),
--       AI-02 발송 시간 추천, AI-03 성과 요약이 빈 화면 없이 보이게 한다.
--
-- 들어가는 것 (모든 시각은 실행 시점 기준 상대값, 세션 타임존 Asia/Seoul)
--  1. [시연] 가을 감사 쿠폰 발송   ONE_TIME COMPLETED, 5일 전 10:00 발송, 쿠폰 '5,000원 할인' 연결
--     - 서울·경기 + 누적구매 10만 원 이상 + 메일 동의 고객. 3건 FAILED, 나머지 SENT
--     - 오픈 약 45%, 클릭 약 15%(클릭 고객은 모두 오픈), 쿠폰 사용 약 5%(고객 페이지 사용 경로라 purchase 는 만들지 않음)
--     - 봇 이벤트 약 5%(발송 3초 뒤 OPEN·CLICK, bot_yn = 'Y') — 지표에서 빠지는 것 확인용
--  2. [시연] 신규 가입 환영 메일    ONE_TIME COMPLETED, 12일 전 14:00 발송, 쿠폰 없음
--     - 최근 90일 가입 + 메일 동의 고객. 오픈 약 35%, 클릭 약 8%
--     - 대시보드 기본 7일 KPI 에는 안 잡히고, 일별 발송 14일 차트·기간 필터(30일)에서 보인다
--  2-1. [시연] 9월 정기 소식        ONE_TIME COMPLETED, 25일 전 11:00 발송, 메일 동의 고객 전체, 오픈 약 50%, 클릭 약 15%
--     - 기본 7일 KPI 에는 없고 기간 필터 '최근 30일'·'전체'에서 보인다. AI-02 는 최근 90일 사람 이벤트 100건 이상이어야 시간대 추천을 하므로 그 몫을 채운다
--  3. [시연] 신규 고객 환영 여정    WORKFLOW ACTIVE (CUSTOMER_REGISTERED)
--     - PRD 6.4 예시 구조(노드 11개, CONDITION 중첩 2단계):
--       TRIGGER → 환영 메일 → 2일 대기 → 클릭했나?
--         예  → 누적구매 10만 원 이상? → 예: 쿠폰 안내 메일(VIP, 10% 쿠폰) / 아니오: 쿠폰 안내 메일(일반, 5,000원 쿠폰) → END
--         아니오 → SMS 리마인드 → END
--     - 고객을 3그룹으로 나눠 6일 전·4일 전 시작(분기까지 완료), 1일 전 시작(대기 중, WAITING)
--     - SMS 수신거부 고객의 SMS 단계는 SKIPPED(발송 직전 재확인 실패) — 시도 수에서 빠진다
--
-- 넣지 않는 것: PENDING·SENDING 발송(로컬 발송 작업이 실제로 보내 버림), purchase(누적구매액은 customer 도메인 쓰기),
--               ai_report(화면에서 생성 버튼으로 확인), A/B
-- 주의
--  - '[시연] 가을 감사 쿠폰 발송' 캠페인이 이미 있으면 전체를 건너뛴다(R__ 재실행 대비). 날짜를 새로 맞추려면
--    로컬 DB 를 다시 만든다(docker compose down -v).
--  - WAITING 인스턴스 next_run_at 은 내일이라, 워크플로우 엔진이 병합되면 로컬에서 이어서 진행된다(Mailpit 으로 수신).
--  - 발송 테이블(send_log·workflow_*)은 팀원2 소유지만 local 시드라 직접 INSERT 한다(운영 DB 에는 들어가지 않음).
--  - 이벤트는 임시 테이블에 모았다가 발생 시각 순으로 넣는다. 대시보드 '최근 이벤트'가 event_id 내림차순이라
--    넣는 순서가 곧 화면 순서다.

DO $$
DECLARE
    v_seed_member   BIGINT;
    v_seg_vip       BIGINT;
    v_seg_new       BIGINT;
    v_tpl_welcome   BIGINT;
    v_tpl_coupon    BIGINT;
    v_tpl_sms       BIGINT;
    v_coupon_amount BIGINT;
    v_coupon_rate   BIGINT;
    v_link_sale     BIGINT;
    v_link_new      BIGINT;
    v_camp_coupon   BIGINT;
    v_camp_welcome  BIGINT;
    v_camp_flow     BIGINT;
    v_camp_news     BIGINT;
    s_trigger BIGINT; s_send1 BIGINT; s_wait BIGINT; s_cond BIGINT; s_cond2 BIGINT;
    s_vip BIGINT; s_normal BIGINT; s_sms BIGINT; s_end_vip BIGINT; s_end_normal BIGINT; s_end_sms BIGINT;
    v_today TIMESTAMPTZ := date_trunc('day', now());
BEGIN
    IF EXISTS (SELECT 1 FROM campaign WHERE name = '[시연] 가을 감사 쿠폰 발송') THEN
        RETURN;
    END IF;

    SELECT member_id INTO v_seed_member FROM member WHERE email = 'seed@withus.local';
    SELECT segment_id INTO v_seg_vip FROM segment WHERE name = '서울·경기 구매 10만 원 이상';
    SELECT segment_id INTO v_seg_new FROM segment WHERE name = '최근 90일 가입·메일 수신동의';
    SELECT template_id INTO v_tpl_welcome FROM template WHERE name = '환영 메일';
    SELECT template_id INTO v_tpl_coupon FROM template WHERE name = '쿠폰 안내 메일';
    SELECT template_id INTO v_tpl_sms FROM template WHERE name = 'SMS 리마인드';
    SELECT coupon_id INTO v_coupon_amount FROM coupon WHERE name = '5,000원 할인';
    SELECT coupon_id INTO v_coupon_rate FROM coupon WHERE name = '10% 할인 (최대 1만 원)';
    IF v_seed_member IS NULL OR v_seg_vip IS NULL OR v_tpl_coupon IS NULL OR v_coupon_amount IS NULL THEN
        RAISE NOTICE '시드 기본 데이터가 없어 시연 발송 데이터를 건너뜁니다';
        RETURN;
    END IF;

    CREATE TEMP TABLE demo_ev (
        send_log_id BIGINT, event_type VARCHAR(10), link_id BIGINT, user_agent VARCHAR(500),
        ip_hash CHAR(64), bot_yn CHAR(1), occurred_at TIMESTAMPTZ
    ) ON COMMIT DROP;

    -- 추적 링크: 발송 때 TrackingLinkService.rewrite 가 등록하는 것과 같은 행 (V30 유니크)
    INSERT INTO track_link (template_id, original_url, link_order)
    VALUES (v_tpl_coupon, 'https://example.com/sale', 1), (v_tpl_welcome, 'https://example.com/new', 1)
    ON CONFLICT (template_id, md5(original_url)) DO NOTHING;
    SELECT link_id INTO v_link_sale FROM track_link WHERE template_id = v_tpl_coupon AND original_url = 'https://example.com/sale';
    SELECT link_id INTO v_link_new FROM track_link WHERE template_id = v_tpl_welcome AND original_url = 'https://example.com/new';

    -- ---------------------------------------------------------------- 1. 일회성 쿠폰 캠페인
    INSERT INTO campaign (name, type, status, segment_id, template_id, coupon_id, scheduled_at, started_at, ended_at, created_by)
    VALUES ('[시연] 가을 감사 쿠폰 발송', 'ONE_TIME', 'COMPLETED', v_seg_vip, v_tpl_coupon, v_coupon_amount,
            v_today - interval '5 days' + interval '10 hours', v_today - interval '5 days' + interval '10 hours',
            v_today - interval '5 days' + interval '10 hours 5 minutes', v_seed_member)
    RETURNING campaign_id INTO v_camp_coupon;

    INSERT INTO send_log (campaign_id, customer_id, recipient, channel, status, kind, priority, error_message, attempt_count, sent_at, created_at, updated_at)
    SELECT v_camp_coupon, c.customer_id, c.email, 'EMAIL',
           CASE WHEN rn <= 3 THEN 'FAILED' ELSE 'SENT' END, 'CAMPAIGN', 3,
           CASE WHEN rn <= 3 THEN 'SMTP_REJECTED' END, 1,
           CASE WHEN rn > 3 THEN v_today - interval '5 days' + interval '10 hours' + make_interval(secs => CAST(rn AS int)) END,
           v_today - interval '5 days' + interval '9 hours 50 minutes',
           v_today - interval '5 days' + interval '10 hours' + make_interval(secs => CAST(rn AS int))
    FROM (SELECT c.*, row_number() OVER (ORDER BY c.customer_id) AS rn
          FROM customer c
          WHERE c.deleted_yn = 'N' AND c.email_consent_yn = 'Y'
            AND c.region_code IN ('SEOUL', 'GYEONGGI') AND c.total_purchase >= 100000) c;

    -- 발송 성공 건마다 쿠폰 1건 (발송 1건당 발급 1건). 사용 처리는 이벤트를 넣은 뒤 클릭 고객 중에서 고른다
    INSERT INTO coupon_issue (coupon_id, customer_id, send_log_id, issued_at)
    SELECT v_coupon_amount, s.customer_id, s.send_log_id, s.sent_at
    FROM send_log s WHERE s.campaign_id = v_camp_coupon AND s.status = 'SENT';

    -- ---------------------------------------------------------------- 2. 일회성 환영 메일 (12일 전)
    IF v_seg_new IS NOT NULL AND v_tpl_welcome IS NOT NULL THEN
        INSERT INTO campaign (name, type, status, segment_id, template_id, scheduled_at, started_at, ended_at, created_by)
        VALUES ('[시연] 신규 가입 환영 메일', 'ONE_TIME', 'COMPLETED', v_seg_new, v_tpl_welcome,
                v_today - interval '12 days' + interval '14 hours', v_today - interval '12 days' + interval '14 hours',
                v_today - interval '12 days' + interval '14 hours 3 minutes', v_seed_member)
        RETURNING campaign_id INTO v_camp_welcome;

        INSERT INTO send_log (campaign_id, customer_id, recipient, channel, status, kind, priority, attempt_count, sent_at, created_at, updated_at)
        SELECT v_camp_welcome, c.customer_id, c.email, 'EMAIL', 'SENT', 'CAMPAIGN', 3, 1,
               v_today - interval '12 days' + interval '14 hours' + make_interval(secs => CAST(c.rn AS int)),
               v_today - interval '12 days' + interval '13 hours 55 minutes',
               v_today - interval '12 days' + interval '14 hours' + make_interval(secs => CAST(c.rn AS int))
        FROM (SELECT c.*, row_number() OVER (ORDER BY c.customer_id) AS rn
              FROM customer c
              WHERE c.deleted_yn = 'N' AND c.email_consent_yn = 'Y' AND c.joined_at >= CURRENT_DATE - 102) c;
    END IF;

    -- ---------------------------------------------------------------- 2-1. 일회성 정기 소식 (25일 전)
    IF v_tpl_welcome IS NOT NULL THEN
        INSERT INTO campaign (name, type, status, segment_id, template_id, scheduled_at, started_at, ended_at, created_by)
        VALUES ('[시연] 9월 정기 소식', 'ONE_TIME', 'COMPLETED', v_seg_vip, v_tpl_welcome,
                v_today - interval '25 days' + interval '11 hours', v_today - interval '25 days' + interval '11 hours',
                v_today - interval '25 days' + interval '11 hours 5 minutes', v_seed_member)
        RETURNING campaign_id INTO v_camp_news;

        INSERT INTO send_log (campaign_id, customer_id, recipient, channel, status, kind, priority, attempt_count, sent_at, created_at, updated_at)
        SELECT v_camp_news, c.customer_id, c.email, 'EMAIL', 'SENT', 'CAMPAIGN', 3, 1,
               v_today - interval '25 days' + interval '11 hours' + make_interval(secs => CAST(c.rn AS int)),
               v_today - interval '25 days' + interval '10 hours 55 minutes',
               v_today - interval '25 days' + interval '11 hours' + make_interval(secs => CAST(c.rn AS int))
        FROM (SELECT c.*, row_number() OVER (ORDER BY c.customer_id) AS rn
              FROM customer c WHERE c.deleted_yn = 'N' AND c.email_consent_yn = 'Y') c;
    END IF;

    -- ---------------------------------------------------------------- 3. 워크플로우 (진행 중)
    IF v_seg_new IS NOT NULL AND v_tpl_welcome IS NOT NULL AND v_tpl_sms IS NOT NULL AND v_coupon_rate IS NOT NULL THEN
        INSERT INTO campaign (name, type, status, segment_id, trigger_type, started_at, created_by)
        VALUES ('[시연] 신규 고객 환영 여정', 'WORKFLOW', 'ACTIVE', v_seg_new, 'CUSTOMER_REGISTERED',
                v_today - interval '6 days' + interval '9 hours', v_seed_member)
        RETURNING campaign_id INTO v_camp_flow;

        -- 노드 배열 순서대로 만든다 (단계별 성과는 step_id 순으로 보여 준다)
        INSERT INTO workflow_step (campaign_id, node_type, config_json, depth)
        VALUES (v_camp_flow, 'TRIGGER', '{"triggerType":"CUSTOMER_REGISTERED"}', 0) RETURNING step_id INTO s_trigger;
        INSERT INTO workflow_step (campaign_id, node_type, config_json, depth)
        VALUES (v_camp_flow, 'SEND_EMAIL', jsonb_build_object('templateId', v_tpl_welcome), 0) RETURNING step_id INTO s_send1;
        INSERT INTO workflow_step (campaign_id, node_type, config_json, depth)
        VALUES (v_camp_flow, 'WAIT', '{"amount":2,"unit":"DAY"}', 0) RETURNING step_id INTO s_wait;
        -- depth = 그 노드까지 지나온 CONDITION 수 (WorkflowService.assignDepth 와 같은 규칙)
        INSERT INTO workflow_step (campaign_id, node_type, config_json, depth)
        VALUES (v_camp_flow, 'CONDITION', '{"condition":"EMAIL_CLICKED"}', 0) RETURNING step_id INTO s_cond;
        INSERT INTO workflow_step (campaign_id, node_type, config_json, depth)
        VALUES (v_camp_flow, 'CONDITION', '{"condition":"PURCHASE_GTE","amount":100000}', 1) RETURNING step_id INTO s_cond2;
        INSERT INTO workflow_step (campaign_id, node_type, config_json, depth)
        VALUES (v_camp_flow, 'SEND_EMAIL', jsonb_build_object('templateId', v_tpl_coupon, 'couponId', v_coupon_rate), 2) RETURNING step_id INTO s_vip;
        INSERT INTO workflow_step (campaign_id, node_type, config_json, depth)
        VALUES (v_camp_flow, 'SEND_EMAIL', jsonb_build_object('templateId', v_tpl_coupon, 'couponId', v_coupon_amount), 2) RETURNING step_id INTO s_normal;
        INSERT INTO workflow_step (campaign_id, node_type, config_json, depth)
        VALUES (v_camp_flow, 'SEND_SMS', jsonb_build_object('templateId', v_tpl_sms), 1) RETURNING step_id INTO s_sms;
        INSERT INTO workflow_step (campaign_id, node_type, config_json, depth)
        VALUES (v_camp_flow, 'END', '{}', 2) RETURNING step_id INTO s_end_vip;
        INSERT INTO workflow_step (campaign_id, node_type, config_json, depth)
        VALUES (v_camp_flow, 'END', '{}', 2) RETURNING step_id INTO s_end_normal;
        INSERT INTO workflow_step (campaign_id, node_type, config_json, depth)
        VALUES (v_camp_flow, 'END', '{}', 1) RETURNING step_id INTO s_end_sms;

        UPDATE workflow_step SET next_step_id = s_send1 WHERE step_id = s_trigger;
        UPDATE workflow_step SET next_step_id = s_wait  WHERE step_id = s_send1;
        UPDATE workflow_step SET next_step_id = s_cond  WHERE step_id = s_wait;
        UPDATE workflow_step SET yes_step_id = s_cond2, no_step_id = s_sms WHERE step_id = s_cond;
        UPDATE workflow_step SET yes_step_id = s_vip, no_step_id = s_normal WHERE step_id = s_cond2;
        UPDATE workflow_step SET next_step_id = s_end_vip    WHERE step_id = s_vip;
        UPDATE workflow_step SET next_step_id = s_end_normal WHERE step_id = s_normal;
        UPDATE workflow_step SET next_step_id = s_end_sms    WHERE step_id = s_sms;

        -- 대상: 최근 90일 가입 + 메일 동의. 그룹(고객 id % 3): 0 = 6일 전 시작, 1 = 4일 전 시작, 2 = 1일 전 시작(대기 중)
        CREATE TEMP TABLE demo_flow ON COMMIT DROP AS
        SELECT c.customer_id, c.email, c.phone, c.sms_consent_yn,
               v_today - make_interval(days => CAST(CASE c.customer_id % 3 WHEN 0 THEN 6 WHEN 1 THEN 4 ELSE 1 END AS int))
                       + interval '9 hours' + make_interval(mins => CAST((c.customer_id % 50) AS int)) AS send1_at,
               c.customer_id % 3 = 2 AS waiting,
               (c.customer_id * 41) % 100 < 30 AS clicked,
               c.total_purchase >= 100000 AS vip
        FROM customer c
        WHERE c.deleted_yn = 'N' AND c.email_consent_yn = 'Y' AND c.joined_at >= CURRENT_DATE - 90;

        INSERT INTO workflow_instance (campaign_id, customer_id, current_step_id, status, next_run_at, created_at, updated_at)
        SELECT v_camp_flow, f.customer_id,
               CASE WHEN f.waiting THEN s_cond
                    WHEN NOT f.clicked THEN s_end_sms
                    WHEN f.vip THEN s_end_vip ELSE s_end_normal END,
               CASE WHEN f.waiting THEN 'WAITING' ELSE 'COMPLETED' END,
               CASE WHEN f.waiting THEN f.send1_at + interval '2 days' END,
               f.send1_at - interval '1 minute',
               CASE WHEN f.waiting THEN f.send1_at ELSE f.send1_at + interval '2 days' END
        FROM demo_flow f;

        -- 1단계 환영 메일
        INSERT INTO send_log (campaign_id, instance_id, step_id, customer_id, recipient, channel, status, kind, priority, attempt_count, sent_at, created_at, updated_at)
        SELECT v_camp_flow, i.instance_id, s_send1, f.customer_id, f.email, 'EMAIL', 'SENT', 'CAMPAIGN', 2, 1,
               f.send1_at, f.send1_at - interval '30 seconds', f.send1_at
        FROM demo_flow f JOIN workflow_instance i ON i.campaign_id = v_camp_flow AND i.customer_id = f.customer_id;

        -- 분기 (2일 뒤): 클릭 + 구매 10만 원 이상 → VIP 쿠폰 메일, 클릭 + 미만 → 일반 쿠폰 메일, 미클릭 → SMS (SMS 수신거부면 SKIPPED)
        INSERT INTO send_log (campaign_id, instance_id, step_id, customer_id, recipient, channel, status, kind, priority, attempt_count, error_message, sent_at, created_at, updated_at)
        SELECT v_camp_flow, i.instance_id,
               CASE WHEN NOT f.clicked THEN s_sms WHEN f.vip THEN s_vip ELSE s_normal END,
               f.customer_id,
               CASE WHEN f.clicked THEN f.email ELSE f.phone END,
               CASE WHEN f.clicked THEN 'EMAIL' ELSE 'SMS' END,
               CASE WHEN NOT f.clicked AND f.sms_consent_yn = 'N' THEN 'SKIPPED' ELSE 'SENT' END,
               'CAMPAIGN', 2,
               CASE WHEN NOT f.clicked AND f.sms_consent_yn = 'N' THEN 0 ELSE 1 END,
               CASE WHEN NOT f.clicked AND f.sms_consent_yn = 'N' THEN 'CONSENT_WITHDRAWN' END,
               CASE WHEN NOT f.clicked AND f.sms_consent_yn = 'N' THEN NULL ELSE f.send1_at + interval '2 days' END,
               f.send1_at + interval '2 days' - interval '30 seconds',
               f.send1_at + interval '2 days'
        FROM demo_flow f JOIN workflow_instance i ON i.campaign_id = v_camp_flow AND i.customer_id = f.customer_id
        WHERE NOT f.waiting;

        -- SEND 노드마다 연결된 쿠폰이 다르다: VIP 경로는 10% 쿠폰, 일반 경로는 5,000원 쿠폰
        INSERT INTO coupon_issue (coupon_id, customer_id, send_log_id, issued_at, used_at)
        SELECT CASE WHEN s.step_id = s_vip THEN v_coupon_rate ELSE v_coupon_amount END,
               s.customer_id, s.send_log_id, s.sent_at,
               CASE WHEN (s.customer_id * 13) % 100 < 40 THEN s.sent_at + interval '5 hours' END
        FROM send_log s WHERE s.campaign_id = v_camp_flow AND s.step_id IN (s_vip, s_normal) AND s.status = 'SENT';

        -- 환영 메일 클릭 이벤트 — CONDITION 판정 근거 (clicked 고객은 반드시 사람 클릭이 있다)
        INSERT INTO demo_ev (send_log_id, event_type, link_id, user_agent, ip_hash, bot_yn, occurred_at)
        SELECT s.send_log_id, t.event_type, CASE WHEN t.event_type = 'CLICK' THEN v_link_new END,
               'Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148',
               encode(sha256(convert_to('demo-ip-' || s.customer_id, 'UTF8')), 'hex'), 'N',
               LEAST(s.sent_at + make_interval(hours => CAST((s.customer_id % 20) AS int), mins => CAST(1 + t.ord AS int)), now() - interval '5 minutes')
        FROM send_log s
        JOIN demo_flow f ON f.customer_id = s.customer_id
        CROSS JOIN (VALUES ('OPEN', 0), ('CLICK', 2)) AS t(event_type, ord)
        WHERE s.campaign_id = v_camp_flow AND s.step_id = s_send1
          AND (f.clicked OR (t.event_type = 'OPEN' AND (s.customer_id * 7) % 100 < 50));

        -- 쿠폰 메일(분기 예: VIP·일반 둘 다) 오픈·클릭
        INSERT INTO demo_ev (send_log_id, event_type, link_id, user_agent, ip_hash, bot_yn, occurred_at)
        SELECT s.send_log_id, t.event_type, CASE WHEN t.event_type = 'CLICK' THEN v_link_sale END,
               'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0 Safari/537.36',
               encode(sha256(convert_to('demo-ip-' || s.customer_id, 'UTF8')), 'hex'), 'N',
               LEAST(s.sent_at + make_interval(hours => CAST(1 + (s.customer_id % 6) AS int), mins => CAST(t.ord AS int)), now() - interval '5 minutes')
        FROM send_log s
        CROSS JOIN (VALUES ('OPEN', 0), ('CLICK', 3)) AS t(event_type, ord)
        WHERE s.campaign_id = v_camp_flow AND s.step_id IN (s_vip, s_normal) AND s.status = 'SENT'
          AND (t.event_type = 'OPEN' OR (s.customer_id * 11) % 100 < 60);
    END IF;

    -- ---------------------------------------------------------------- 일회성 캠페인 이벤트 (1·2·2-1 공통)
    -- 사람 이벤트: 해시 h = (고객 id * 31 + 캠페인 id) % 100. 오픈 h < 오픈율, 클릭 h < 클릭율(클릭 고객은 오픈도 있다)
    -- 시각은 발송 후 0~29시간에 퍼뜨려 AI-02 요일·시간대 집계가 한 시간대에 몰리지 않게 한다
    INSERT INTO demo_ev (send_log_id, event_type, link_id, user_agent, ip_hash, bot_yn, occurred_at)
    SELECT s.send_log_id, t.event_type,
           CASE WHEN t.event_type = 'CLICK' THEN CASE WHEN s.campaign_id = v_camp_coupon THEN v_link_sale ELSE v_link_new END END,
           CASE WHEN s.customer_id % 2 = 0
                THEN 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0 Safari/537.36'
                ELSE 'Mozilla/5.0 (Linux; Android 14; SM-S921N) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36' END,
           encode(sha256(convert_to('demo-ip-' || s.customer_id, 'UTF8')), 'hex'), 'N',
           LEAST(s.sent_at + make_interval(hours => CAST((s.customer_id * 7) % 30 AS int), mins => CAST(2 + (s.customer_id % 50) + t.ord AS int)),
                 now() - interval '5 minutes')
    FROM send_log s
    CROSS JOIN (VALUES ('OPEN', 0), ('CLICK', 1)) AS t(event_type, ord)
    WHERE s.campaign_id IN (v_camp_coupon, v_camp_welcome, v_camp_news) AND s.status = 'SENT'
      AND (s.customer_id * 31 + s.campaign_id) % 100
          < CASE WHEN t.event_type = 'OPEN'
                 THEN CASE s.campaign_id WHEN v_camp_coupon THEN 45 WHEN v_camp_news THEN 50 ELSE 35 END
                 ELSE CASE s.campaign_id WHEN v_camp_coupon THEN 15 WHEN v_camp_news THEN 15 ELSE 8 END END;

    -- 쿠폰 사용: 사람 클릭이 있는 고객 중 앞쪽 40%(최소 1명)가 첫 클릭 20분 뒤 고객 페이지에서 사용
    UPDATE coupon_issue ci
    SET used_at = LEAST(x.first_click + interval '20 minutes', now() - interval '1 minute'), updated_at = now()
    FROM (SELECT ci2.issue_id, min(e.occurred_at) AS first_click,
                 row_number() OVER (ORDER BY ci2.issue_id) AS rn, count(*) OVER () AS n
          FROM coupon_issue ci2
          JOIN send_log s ON s.send_log_id = ci2.send_log_id AND s.campaign_id = v_camp_coupon
          JOIN demo_ev e ON e.send_log_id = ci2.send_log_id AND e.event_type = 'CLICK' AND e.bot_yn = 'N'
          GROUP BY ci2.issue_id) x
    WHERE ci.issue_id = x.issue_id AND x.rn <= GREATEST(1, ceil(x.n * 0.4));

    -- 봇 이벤트: 발송 3초 뒤 보안 스캐너가 오픈·클릭 (bot_yn = 'Y', 모든 지표에서 빠진다)
    INSERT INTO demo_ev (send_log_id, event_type, link_id, user_agent, ip_hash, bot_yn, occurred_at)
    SELECT s.send_log_id, t.event_type, CASE WHEN t.event_type = 'CLICK' THEN v_link_sale END,
           'Mozilla/5.0 (compatible; SecurityScanner/1.0)',
           encode(sha256(convert_to('demo-bot-ip', 'UTF8')), 'hex'), 'Y',
           s.sent_at + interval '3 seconds'
    FROM send_log s
    CROSS JOIN (VALUES ('OPEN'), ('CLICK')) AS t(event_type)
    WHERE s.campaign_id = v_camp_coupon AND s.status = 'SENT' AND (s.customer_id * 53) % 100 < 5;

    INSERT INTO track_event (send_log_id, event_type, link_id, user_agent, ip_hash, bot_yn, occurred_at)
    SELECT send_log_id, event_type, link_id, user_agent, ip_hash, bot_yn, occurred_at
    FROM demo_ev ORDER BY occurred_at, send_log_id, event_type DESC;
END $$;
