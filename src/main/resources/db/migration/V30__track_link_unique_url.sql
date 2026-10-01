-- 팀원3(tracking) 대역 V30~V39
-- 발송 시 TrackingLinkService.rewrite 가 템플릿 링크를 자동 등록한다. 같은 템플릿의 같은 URL 은 한 행만 둔다.
-- original_url 은 길이 제한이 없는 TEXT 라 btree 키 크기 한도를 피하려고 md5 로 유니크를 건다.
-- 동시 발송에서 INSERT ... ON CONFLICT (template_id, md5(original_url)) DO NOTHING 으로 중복 없이 등록한다.
CREATE UNIQUE INDEX uq_track_link_template_url ON track_link (template_id, md5(original_url));
