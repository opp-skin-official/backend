-- 문진 상담(C-01 → C-02 → R-01). conversations.kind = 'CONSULT'
-- 문진은 선택지로 받아 Model A 값(고민 9종, 부위 6개, 시작·기간·변화, 심한 정도 1~5)에 그대로 맞춘다.

ALTER TABLE conversations ADD COLUMN state VARCHAR(20); -- CONSULT: COMPLETED(리포트 생성), REFERRED(위험 신호로 의료진 안내)

-- C-02 상담 내용 확인에서 확정한 문진 답 (ConsultRequest 그대로)
CREATE TABLE consultation_summaries (
    conversation_id BIGINT      PRIMARY KEY REFERENCES conversations (id) ON DELETE CASCADE,
    answers         JSONB       NOT NULL,
    red_flag        BOOLEAN     NOT NULL,
    confirmed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- R-01 관리 리포트: 모델 결과를 당시 그대로 보존한다 (internal_audit 제외).
CREATE TABLE care_reports (
    id              BIGSERIAL   PRIMARY KEY,
    conversation_id BIGINT      NOT NULL UNIQUE REFERENCES conversations (id) ON DELETE CASCADE,
    user_id         BIGINT      NOT NULL REFERENCES users (id),
    analysis        JSONB       NOT NULL,   -- Model A /analyze: cause_solution_report, skin3d_payload, ingredient_profile
    routine_plan    JSONB,                  -- Model B /routine: routine_plan (만들지 못했으면 null, 다시 만들 수 있음)
    applied_plan_id BIGINT      REFERENCES care_plans (id), -- 이 루틴으로 시작한 관리 계획
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX care_reports_user_idx ON care_reports (user_id, created_at DESC);
