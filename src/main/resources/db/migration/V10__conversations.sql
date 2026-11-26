-- 상담 대화 (docs/data-model.md 4.4). 지금은 피부 Q&A(QNA, Model B /chat)만 쓴다.
-- 문진 상담(CONSULT)의 상태·결과·요약 컬럼은 그 기능을 만들 때 추가한다.

CREATE TABLE conversations (
    id                BIGSERIAL    PRIMARY KEY,
    user_id           BIGINT       NOT NULL REFERENCES users (id),
    kind              VARCHAR(20)  NOT NULL,                -- QNA (이후 CONSULT, ROUTINE_ADJUST)
    use_saved_profile BOOLEAN      NOT NULL DEFAULT TRUE,   -- 피부 유형·생활 환경·주의 성분을 AI에 함께 보낼지
    title             VARCHAR(100),                         -- 첫 질문 앞부분
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX conversations_user_idx ON conversations (user_id, updated_at DESC);

CREATE TABLE conversation_messages (
    id              BIGSERIAL   PRIMARY KEY,
    conversation_id BIGINT      NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    role            VARCHAR(10) NOT NULL,                 -- USER, ASSISTANT
    status          VARCHAR(10) NOT NULL DEFAULT 'DONE',  -- DONE, BLOCKED(안전 검사에 걸려 안내 문구로 바꿈)
    content         TEXT        NOT NULL,
    payload         JSONB,      -- 답변 부가 정보: action_tips, follow_up_questions, evidence_ids, refer_to_clinician, notice, referral_notice
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX conversation_messages_conversation_idx ON conversation_messages (conversation_id, id);
