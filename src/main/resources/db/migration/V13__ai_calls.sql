-- AI 모델 호출 기록: 사용자별 호출 횟수 제한(최근 24시간)에 쓴다.
CREATE TABLE ai_calls (
    id         BIGSERIAL   PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id),
    kind       VARCHAR(20) NOT NULL, -- CHAT(피부 Q&A 질문), CONSULT(문진 분석·후속 루틴·루틴 다시 만들기)
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ai_calls_user_kind_time ON ai_calls (user_id, kind, created_at);
