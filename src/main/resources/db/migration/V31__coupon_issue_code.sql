-- 팀원3(coupon) 대역 V30~V39
-- 오프라인 바코드 쿠폰 (project #51, PRD F-10 변경): 발급마다 매장 바코드에 담을 12자리 숫자 쿠폰 번호를 둔다.
-- URL 토큰(token, UUID)은 그대로 두고, 바코드는 짧아야 스캐너가 잘 읽고 직원이 손으로도 칠 수 있어 따로 둔다.
-- 순번이 아니라 추측할 수 없는 값이어야 한다(6장 7번 취지): gen_random_uuid()(pg_strong_random)의 60비트를 10^12 로 나눈 나머지.

CREATE FUNCTION coupon_issue_new_code() RETURNS char(12)
LANGUAGE sql VOLATILE AS $$
	SELECT lpad(((('x' || substr(replace(gen_random_uuid()::text, '-', ''), 1, 15))::bit(60)::bigint)
		% 1000000000000)::text, 12, '0')
$$;

ALTER TABLE coupon_issue ADD COLUMN code char(12);

-- 기존 발급분 채우기. 1조 칸이라 겹칠 일은 거의 없지만, 겹치면 겹친 행만 다시 뽑는다
DO $$
BEGIN
	UPDATE coupon_issue SET code = coupon_issue_new_code() WHERE code IS NULL;
	LOOP
		UPDATE coupon_issue SET code = coupon_issue_new_code()
		WHERE issue_id IN (
			SELECT issue_id FROM (
				SELECT issue_id, row_number() OVER (PARTITION BY code ORDER BY issue_id) AS rn FROM coupon_issue
			) d WHERE d.rn > 1
		);
		EXIT WHEN NOT FOUND;
	END LOOP;
END
$$;

-- 새 발급은 기본값으로 번호를 받는다. 드물게 번호가 겹치면 INSERT ... ON CONFLICT DO NOTHING 이 0건이 되고
-- CouponServiceImpl.issue 가 다시 시도한다(새 기본값으로 다른 번호)
ALTER TABLE coupon_issue
	ALTER COLUMN code SET DEFAULT coupon_issue_new_code(),
	ALTER COLUMN code SET NOT NULL,
	ADD CONSTRAINT uq_coupon_issue_code UNIQUE (code);
