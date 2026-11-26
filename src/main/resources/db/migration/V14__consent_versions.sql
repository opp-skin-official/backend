-- 동의한 약관 버전 (약관이 바뀌면 다시 동의받는다). 버전이 없는 예전 기록(민감정보 동의)은 NULL.
ALTER TABLE consents ADD COLUMN version VARCHAR(20);
CREATE INDEX consents_user_type_idx ON consents (user_id, type) WHERE revoked_at IS NULL;
