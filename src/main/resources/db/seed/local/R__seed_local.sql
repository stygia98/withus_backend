-- local 프로필 전용 시연 데이터 (DB_SCHEMA.md 9장). 운영 DB 에는 들어가지 않는다.
-- 반복 실행 마이그레이션(R__): 내용이 바뀌면 다시 실행되므로 모든 INSERT 는 여러 번 실행해도 결과가 같다.
-- 고객 100명, 세그먼트 2개, 템플릿 3개(메일 2, SMS 1), 쿠폰 2개(정액·정률)

-- 시드 작성자 계정: Flyway 가 OWNER 생성(앱 시작 후)보다 먼저 실행되므로 created_by 용으로 둔다.
-- 비활성(active_yn = 'N')이고 BCrypt 가 아닌 비밀번호라 로그인할 수 없다.
INSERT INTO member (email, password, name, role, active_yn)
VALUES ('seed@withus.local', 'SEED-ACCOUNT-NO-LOGIN', '시드 데이터', 'STAFF', 'N')
ON CONFLICT (email) DO NOTHING;

-- 고객 100명
--  지역: SEOUL 40 / GYEONGGI 30 / BUSAN 30, 나이 20~60세, 가입일 최근 3~300일, 누적구매액 0~19만 원
--  이름 없음 4명(25·50·75·100번, 치환자 기본값 확인용), 메일 수신거부 10명(10의 배수), SMS 수신거부 33명(3의 배수)
--  99번: 메일 동의 일시가 2년 1일 전 (F-12 수신동의 2년 안내 확인용)
INSERT INTO customer (name, email, phone, region_code, birth_date, joined_at, total_purchase,
                      email_consent_yn, email_consent_at, sms_consent_yn, sms_consent_at, source)
SELECT CASE WHEN i % 25 = 0 THEN NULL ELSE '고객' || lpad(i::text, 3, '0') END,
       'customer' || lpad(i::text, 3, '0') || '@example.com',
       '0100000' || lpad(i::text, 4, '0'),
       CASE WHEN i % 10 < 4 THEN 'SEOUL' WHEN i % 10 < 7 THEN 'GYEONGGI' ELSE 'BUSAN' END,
       (CURRENT_DATE - make_interval(years => 20 + i % 41, days => i))::date,
       CURRENT_DATE - i * 3,
       (i * 37 % 20) * 10000,
       CASE WHEN i % 10 = 0 THEN 'N' ELSE 'Y' END,
       CASE WHEN i % 10 = 0 THEN NULL
            WHEN i = 99 THEN now() - INTERVAL '2 years 1 day'
            ELSE now() - make_interval(days => i * 3) END,
       CASE WHEN i % 3 = 0 THEN 'N' ELSE 'Y' END,
       CASE WHEN i % 3 = 0 THEN NULL ELSE now() - make_interval(days => i * 3) END,
       'UPLOAD'
FROM generate_series(1, 100) AS i
ON CONFLICT (email) WHERE deleted_yn = 'N' DO NOTHING;

-- 세그먼트 2개 (규칙 형식: DB_SCHEMA.md 5.1)
INSERT INTO segment (name, description, created_by)
SELECT v.name, v.description, m.member_id
FROM (VALUES
        ('서울·경기 구매 10만 원 이상', 'PRD 10.3 시연 시나리오 세그먼트'),
        ('최근 90일 가입·메일 수신동의', '신규 고객 환영 캠페인용')
     ) AS v(name, description)
JOIN member m ON m.email = 'seed@withus.local'
WHERE NOT EXISTS (SELECT 1 FROM segment s WHERE s.name = v.name);

INSERT INTO segment_rule (segment_id, rule_json)
SELECT s.segment_id, v.rule_json::jsonb
FROM (VALUES
        ('서울·경기 구매 10만 원 이상',
         '{"operator":"AND","groups":[{"operator":"AND","conditions":[{"field":"region","op":"IN","value":["SEOUL","GYEONGGI"]},{"field":"totalPurchase","op":"GTE","value":100000}]}]}'),
        ('최근 90일 가입·메일 수신동의',
         '{"operator":"AND","groups":[{"operator":"AND","conditions":[{"field":"joinedAt","op":"IN_LAST_DAYS","value":90},{"field":"emailConsent","op":"EQ","value":"Y"}]}]}')
     ) AS v(name, rule_json)
JOIN segment s ON s.name = v.name
ON CONFLICT (segment_id) DO NOTHING;

-- 템플릿 3개 (메일 2, SMS 1). (광고)·발신자·수신거부 문구는 발송 시 시스템이 넣으므로 본문에 쓰지 않는다
INSERT INTO template (channel, name, subject, body, ad_yn, created_by)
SELECT v.channel, v.name, v.subject, v.body, 'Y', m.member_id
FROM (VALUES
        ('EMAIL', '환영 메일', '{{name|고객}}님, 위드어스에 오신 것을 환영합니다',
         '<p>안녕하세요, {{name|고객}}님!</p><p>위드어스 회원이 되신 것을 환영합니다.</p><p><a href="https://example.com/new">신상품 보러 가기</a></p>'),
        ('EMAIL', '쿠폰 안내 메일', '{{name|고객}}님께 드리는 할인 쿠폰',
         '<p>{{name|고객}}님, 감사의 마음을 담아 쿠폰을 드립니다.</p><p><a href="{{couponUrl}}">쿠폰 받기</a></p><p><a href="https://example.com/sale">할인 상품 보기</a></p>'),
        ('SMS', 'SMS 리마인드', NULL,
         '{{name|고객}}님, 장바구니에 담아 두신 상품이 기다리고 있어요.')
     ) AS v(channel, name, subject, body)
JOIN member m ON m.email = 'seed@withus.local'
WHERE NOT EXISTS (SELECT 1 FROM template t WHERE t.name = v.name);

-- 쿠폰 2개 (정액·정률). 유효기간은 처음 시드가 들어간 날부터 60일
INSERT INTO coupon (name, discount_type, discount_value, max_discount_amount, valid_from, valid_to)
SELECT v.name, v.discount_type, v.discount_value, v.max_discount_amount, CURRENT_DATE, CURRENT_DATE + 60
FROM (VALUES
        ('5,000원 할인', 'AMOUNT', 5000, NULL::int),
        ('10% 할인 (최대 1만 원)', 'RATE', 10, 10000)
     ) AS v(name, discount_type, discount_value, max_discount_amount)
WHERE NOT EXISTS (SELECT 1 FROM coupon c WHERE c.name = v.name);
