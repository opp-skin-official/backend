-- AI가 만든 답변·리포트에 대한 사용자 신고 (Google Play 생성형 AI 정책: 앱 안에서 신고할 수 있어야 함)
-- message_id가 있으면 채팅 답변, 없으면 그 상담(conversation)의 리포트를 신고한 것.
CREATE TABLE ai_content_reports (
    id              BIGSERIAL    PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES users (id),
    conversation_id BIGINT       NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    message_id      BIGINT       REFERENCES conversation_messages (id) ON DELETE CASCADE,
    reason          VARCHAR(20)  NOT NULL,  -- OFFENSIVE, HARMFUL, MEDICAL, INACCURATE, OTHER
    detail          VARCHAR(500),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 같은 대상은 한 번만 (다시 신고하면 사유를 고친다)
CREATE UNIQUE INDEX ai_content_reports_target_uq ON ai_content_reports (user_id, conversation_id, COALESCE(message_id, 0));
CREATE INDEX ai_content_reports_created_idx ON ai_content_reports (created_at DESC);
