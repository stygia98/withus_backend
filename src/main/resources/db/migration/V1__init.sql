-- 위드어스 초기 스키마 (18개 테이블)
-- 기준 문서: docs/db/DB_SCHEMA.md 4장 — 이 파일과 문서를 함께 갱신한다
-- 적용 후 절대 수정하지 않는다. 변경은 새 V{n} 파일로만 (번호 대역: docs/workflow-git.md)

-- =========================================================
-- 1. member
-- =========================================================
CREATE TABLE member (
    member_id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email               VARCHAR(255) NOT NULL UNIQUE,
    password            VARCHAR(100) NOT NULL,                 -- BCrypt
    name                VARCHAR(50)  NOT NULL,
    role                VARCHAR(20)  NOT NULL CHECK (role IN ('OWNER','MANAGER','STAFF')),
    active_yn           CHAR(1)      NOT NULL DEFAULT 'Y' CHECK (active_yn IN ('Y','N')),
    refresh_token_hash  VARCHAR(128),                          -- 사용자당 세션 1개
    failed_login_count  INT          NOT NULL DEFAULT 0,
    locked_until        TIMESTAMPTZ,                           -- 5회 실패 시 now()+5분
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- =========================================================
-- 2. customer
-- =========================================================
CREATE TABLE customer (
    customer_id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name                 VARCHAR(50),
    email                VARCHAR(255) NOT NULL,                -- 소문자·trim 정규화
    phone                VARCHAR(20),                          -- 숫자만
    region_code          VARCHAR(20),                          -- SEOUL, GYEONGGI ...
    birth_date           DATE,                                 -- 만 나이 계산
    joined_at            DATE         NOT NULL,
    total_purchase       BIGINT       NOT NULL DEFAULT 0 CHECK (total_purchase >= 0),
    email_consent_yn     CHAR(1)      NOT NULL DEFAULT 'N' CHECK (email_consent_yn IN ('Y','N')),
    email_consent_at     TIMESTAMPTZ,
    sms_consent_yn       CHAR(1)      NOT NULL DEFAULT 'N' CHECK (sms_consent_yn IN ('Y','N')),
    sms_consent_at       TIMESTAMPTZ,
    dormant_yn           CHAR(1)      NOT NULL DEFAULT 'N' CHECK (dormant_yn IN ('Y','N')),
    dormant_at           TIMESTAMPTZ,
    consent_notified_at  TIMESTAMPTZ,                          -- F-12 직전 안내 일시
    source               VARCHAR(10)  NOT NULL CHECK (source IN ('MANUAL','UPLOAD')),
    deleted_yn           CHAR(1)      NOT NULL DEFAULT 'N' CHECK (deleted_yn IN ('Y','N')),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 삭제되지 않은 고객 사이에서만 이메일 유일 (재등록 허용)
CREATE UNIQUE INDEX uq_customer_email_active ON customer (email) WHERE deleted_yn = 'N';
CREATE INDEX ix_customer_region         ON customer (region_code);
CREATE INDEX ix_customer_joined_at      ON customer (joined_at);
CREATE INDEX ix_customer_birth_date     ON customer (birth_date);
CREATE INDEX ix_customer_total_purchase ON customer (total_purchase);
CREATE INDEX ix_customer_phone          ON customer (phone);

-- =========================================================
-- 3. consent_history
-- =========================================================
CREATE TABLE consent_history (
    history_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id  BIGINT      NOT NULL REFERENCES customer (customer_id),
    channel      VARCHAR(10) NOT NULL CHECK (channel IN ('EMAIL','SMS')),
    before_yn    CHAR(1)     CHECK (before_yn IN ('Y','N')),
    after_yn     CHAR(1)     NOT NULL CHECK (after_yn IN ('Y','N')),
    source       VARCHAR(20) NOT NULL CHECK (source IN ('ADMIN','UPLOAD','UNSUBSCRIBE','BOUNCE','COMPLAINT')),
    note         VARCHAR(500),                                 -- 관리자 해제 시 증빙 메모
    changed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_consent_history_customer ON consent_history (customer_id);

-- =========================================================
-- 4. suppression (수신거부 목록, 영구 보관)
-- =========================================================
CREATE TABLE suppression (
    suppression_id  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    channel         VARCHAR(10)  NOT NULL CHECK (channel IN ('EMAIL','SMS')),
    value           VARCHAR(255) NOT NULL,                     -- 정규화된 이메일 또는 휴대폰
    reason          VARCHAR(20)  NOT NULL CHECK (reason IN ('UNSUBSCRIBE','BOUNCE','COMPLAINT')),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_suppression UNIQUE (channel, value)
);

-- =========================================================
-- 5. segment / 6. segment_rule
-- =========================================================
CREATE TABLE segment (
    segment_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name         VARCHAR(100) NOT NULL,
    description  VARCHAR(500),
    created_by   BIGINT       NOT NULL REFERENCES member (member_id),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE segment_rule (
    rule_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    segment_id  BIGINT      NOT NULL UNIQUE REFERENCES segment (segment_id) ON DELETE CASCADE,
    rule_json   JSONB       NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =========================================================
-- 7. template
-- =========================================================
CREATE TABLE template (
    template_id  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    channel      VARCHAR(10)  NOT NULL CHECK (channel IN ('EMAIL','SMS')),
    name         VARCHAR(100) NOT NULL,
    subject      VARCHAR(200),                                 -- EMAIL만 필수 (애플리케이션 검증)
    body         TEXT         NOT NULL,
    ad_yn        CHAR(1)      NOT NULL DEFAULT 'Y' CHECK (ad_yn IN ('Y','N')),
    created_by   BIGINT       NOT NULL REFERENCES member (member_id),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- =========================================================
-- 8. coupon (campaign보다 먼저 생성: FK 참조)
-- =========================================================
CREATE TABLE coupon (
    coupon_id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name                 VARCHAR(100) NOT NULL,
    discount_type        VARCHAR(10)  NOT NULL CHECK (discount_type IN ('AMOUNT','RATE')),
    discount_value       INT          NOT NULL CHECK (discount_value > 0),   -- 원 또는 %
    max_discount_amount  INT          CHECK (max_discount_amount > 0),       -- RATE 상한
    valid_from           DATE         NOT NULL,
    valid_to             DATE         NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_coupon_period CHECK (valid_to >= valid_from),
    CONSTRAINT ck_coupon_rate   CHECK (discount_type <> 'RATE' OR (discount_value <= 100 AND max_discount_amount IS NOT NULL))
);

-- =========================================================
-- 9. campaign
-- =========================================================
CREATE TABLE campaign (
    campaign_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    type          VARCHAR(10)  NOT NULL CHECK (type IN ('ONE_TIME','WORKFLOW')),
    status        VARCHAR(10)  NOT NULL DEFAULT 'DRAFT'
                  CHECK (status IN ('DRAFT','SCHEDULED','ACTIVE','PAUSED','COMPLETED')),
    segment_id    BIGINT       NOT NULL REFERENCES segment (segment_id),
    template_id   BIGINT       REFERENCES template (template_id),   -- 일회성만
    coupon_id     BIGINT       REFERENCES coupon (coupon_id),       -- 일회성만, 선택
    scheduled_at  TIMESTAMPTZ,
    trigger_type  VARCHAR(30)  CHECK (trigger_type IN ('SEGMENT_SCHEDULED','CUSTOMER_REGISTERED')),
    started_at    TIMESTAMPTZ,
    ended_at      TIMESTAMPTZ,
    created_by    BIGINT       NOT NULL REFERENCES member (member_id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_campaign_type_fields CHECK (
        (type = 'ONE_TIME' AND trigger_type IS NULL) OR
        (type = 'WORKFLOW' AND template_id IS NULL AND coupon_id IS NULL AND trigger_type IS NOT NULL)
    )
);
CREATE INDEX ix_campaign_status ON campaign (status);

-- =========================================================
-- 10. ab_test
-- =========================================================
CREATE TABLE ab_test (
    ab_test_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    campaign_id       BIGINT       NOT NULL UNIQUE REFERENCES campaign (campaign_id) ON DELETE CASCADE,
    subject_a         VARCHAR(200) NOT NULL,
    subject_b         VARCHAR(200) NOT NULL,
    sample_ratio      NUMERIC(3,2) NOT NULL DEFAULT 0.20 CHECK (sample_ratio > 0 AND sample_ratio < 1),
    decide_after_min  INT          NOT NULL DEFAULT 240 CHECK (decide_after_min > 0),
    winner            CHAR(1)      CHECK (winner IN ('A','B')),
    decided_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- =========================================================
-- 11. workflow_step
-- =========================================================
CREATE TABLE workflow_step (
    step_id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    campaign_id   BIGINT      NOT NULL REFERENCES campaign (campaign_id) ON DELETE CASCADE,
    node_type     VARCHAR(20) NOT NULL
                  CHECK (node_type IN ('TRIGGER','WAIT','CONDITION','SEND_EMAIL','SEND_SMS','END')),
    config_json   JSONB       NOT NULL DEFAULT '{}'::jsonb,   -- 템플릿·쿠폰·대기 시간·조건
    next_step_id  BIGINT      REFERENCES workflow_step (step_id),
    yes_step_id   BIGINT      REFERENCES workflow_step (step_id),
    no_step_id    BIGINT      REFERENCES workflow_step (step_id),
    depth         SMALLINT    NOT NULL DEFAULT 0 CHECK (depth BETWEEN 0 AND 2),  -- CONDITION 중첩 수
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_workflow_step_campaign ON workflow_step (campaign_id);

-- =========================================================
-- 12. workflow_instance
-- =========================================================
CREATE TABLE workflow_instance (
    instance_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    campaign_id      BIGINT      NOT NULL REFERENCES campaign (campaign_id),
    customer_id      BIGINT      NOT NULL REFERENCES customer (customer_id),
    current_step_id  BIGINT      NOT NULL REFERENCES workflow_step (step_id),
    status           VARCHAR(10) NOT NULL DEFAULT 'WAITING'
                     CHECK (status IN ('WAITING','RUNNING','COMPLETED','FAILED','CANCELLED')),
    next_run_at      TIMESTAMPTZ,              -- NULL + WAITING = 직전 SEND 발송 완료 대기
    retry_count      INT         NOT NULL DEFAULT 0,
    last_error       TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),  -- RUNNING 10분 초과 복구 판단에 사용
    CONSTRAINT uq_workflow_instance UNIQUE (campaign_id, customer_id)
);
CREATE INDEX ix_workflow_instance_due ON workflow_instance (status, next_run_at);

-- =========================================================
-- 13. send_log (발송 큐 겸 이력)
-- =========================================================
CREATE TABLE send_log (
    send_log_id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    campaign_id          BIGINT       REFERENCES campaign (campaign_id),       -- NOTICE·TEST는 NULL
    instance_id          BIGINT       REFERENCES workflow_instance (instance_id),
    step_id              BIGINT       REFERENCES workflow_step (step_id),
    customer_id          BIGINT       REFERENCES customer (customer_id),       -- TEST는 NULL
    recipient            VARCHAR(255) NOT NULL,                                -- 적재 시점 이메일/휴대폰
    channel              VARCHAR(10)  NOT NULL CHECK (channel IN ('EMAIL','SMS')),
    ab_variant           CHAR(1)      CHECK (ab_variant IN ('A','B')),
    status               VARCHAR(10)  NOT NULL DEFAULT 'PENDING'
                         CHECK (status IN ('PENDING','SENDING','SENT','FAILED','SKIPPED','BOUNCED')),
    kind                 VARCHAR(10)  NOT NULL DEFAULT 'CAMPAIGN' CHECK (kind IN ('CAMPAIGN','NOTICE','TEST')),
    priority             SMALLINT     NOT NULL CHECK (priority BETWEEN 1 AND 3),  -- 1 TEST, 2 WORKFLOW·NOTICE, 3 대량
    provider_message_id  VARCHAR(200),
    tracking_token       UUID         NOT NULL DEFAULT gen_random_uuid(),
    attempt_count        INT          NOT NULL DEFAULT 0,
    next_attempt_at      TIMESTAMPTZ,
    error_message        VARCHAR(500),                                         -- 사유 코드 포함 (COUPON_INVALID, UNKNOWN_RESULT ...)
    sent_at              TIMESTAMPTZ,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),                  -- SENDING 10분 초과 판단에 사용
    CONSTRAINT uq_send_log_step           UNIQUE (instance_id, step_id),
    CONSTRAINT uq_send_log_tracking_token UNIQUE (tracking_token)
);
-- 일회성·A/B: 한 캠페인에서 고객당 1건
CREATE UNIQUE INDEX uq_send_log_one_time ON send_log (campaign_id, customer_id) WHERE instance_id IS NULL;
-- 발송 큐 조회용
CREATE INDEX ix_send_log_queue    ON send_log (priority, next_attempt_at, send_log_id) WHERE status = 'PENDING';
CREATE INDEX ix_send_log_sending  ON send_log (updated_at) WHERE status = 'SENDING';
CREATE INDEX ix_send_log_campaign ON send_log (campaign_id);
CREATE INDEX ix_send_log_customer ON send_log (customer_id);
CREATE INDEX ix_send_log_provider ON send_log (provider_message_id);

-- =========================================================
-- 14. track_link / 15. track_event
-- =========================================================
CREATE TABLE track_link (
    link_id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    template_id   BIGINT      NOT NULL REFERENCES template (template_id),  -- 삭제하지 않음
    original_url  TEXT        NOT NULL,
    link_order    INT         NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_track_link_template ON track_link (template_id);

CREATE TABLE track_event (
    event_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    send_log_id  BIGINT       NOT NULL REFERENCES send_log (send_log_id),
    event_type   VARCHAR(10)  NOT NULL CHECK (event_type IN ('OPEN','CLICK')),
    link_id      BIGINT       REFERENCES track_link (link_id),          -- CLICK만
    user_agent   VARCHAR(500),
    ip_hash      CHAR(64),                                              -- SHA-256 hex
    bot_yn       CHAR(1)      NOT NULL DEFAULT 'N' CHECK (bot_yn IN ('Y','N')),
    occurred_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_track_event_send_log ON track_event (send_log_id, event_type);
CREATE INDEX ix_track_event_occurred ON track_event (occurred_at);

-- =========================================================
-- 16. coupon_issue / 17. purchase
-- =========================================================
CREATE TABLE coupon_issue (
    issue_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    coupon_id    BIGINT      NOT NULL REFERENCES coupon (coupon_id),
    customer_id  BIGINT      NOT NULL REFERENCES customer (customer_id),
    send_log_id  BIGINT      UNIQUE REFERENCES send_log (send_log_id),  -- 발송 1건당 발급 1건
    token        UUID        NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    issued_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    used_at      TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_coupon_issue_coupon_customer ON coupon_issue (coupon_id, customer_id);

CREATE TABLE purchase (
    purchase_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id      BIGINT      NOT NULL REFERENCES customer (customer_id),
    amount           BIGINT      NOT NULL CHECK (amount > 0),
    coupon_issue_id  BIGINT      REFERENCES coupon_issue (issue_id),
    created_by       BIGINT      NOT NULL REFERENCES member (member_id),   -- 관리자 등록만 존재
    purchased_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_purchase_customer ON purchase (customer_id);
-- 쿠폰 1건은 구매 1건에만 연결
CREATE UNIQUE INDEX uq_purchase_coupon_issue ON purchase (coupon_issue_id) WHERE coupon_issue_id IS NOT NULL;

-- =========================================================
-- 18. ai_report
-- =========================================================
CREATE TABLE ai_report (
    report_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    campaign_id  BIGINT      NOT NULL REFERENCES campaign (campaign_id),
    report_type  VARCHAR(30) NOT NULL DEFAULT 'CAMPAIGN_SUMMARY',
    input_json   JSONB       NOT NULL,             -- 집계 지표만, 개인정보 없음
    content      TEXT        NOT NULL,
    model        VARCHAR(50) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_ai_report_campaign ON ai_report (campaign_id);
