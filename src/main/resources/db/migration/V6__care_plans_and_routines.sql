-- 관리 계획(목표일)과 루틴 (Figma "루틴", "루틴-관리 계획 수정")

CREATE TABLE care_plans (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users (id),
    goal_type   VARCHAR(20)  NOT NULL, -- VACATION, OUTING, OVERNIGHT, CUSTOM
    goal_label  VARCHAR(30),           -- 직접 입력일 때 이름
    focus_goal  VARCHAR(30)  NOT NULL, -- BASIC_ROUTINE, PRODUCT_REACTION, SKIN_CHANGE
    start_date  DATE         NOT NULL,
    target_date DATE         NOT NULL,
    status      VARCHAR(10)  NOT NULL, -- ACTIVE, ARCHIVED
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (target_date >= start_date)
);

-- 사용자당 진행 중인 계획은 하나
CREATE UNIQUE INDEX care_plans_one_active_per_user ON care_plans (user_id) WHERE status = 'ACTIVE';

CREATE TABLE routine_items (
    id           BIGSERIAL    PRIMARY KEY,
    care_plan_id BIGINT       NOT NULL REFERENCES care_plans (id),
    time_of_day  VARCHAR(10)  NOT NULL, -- MORNING, EVENING
    title        VARCHAR(100) NOT NULL,
    subtitle     VARCHAR(100),
    sort_order   INT          NOT NULL
);

CREATE INDEX routine_items_plan_idx ON routine_items (care_plan_id);

CREATE TABLE routine_checks (
    routine_item_id BIGINT      NOT NULL REFERENCES routine_items (id),
    check_date      DATE        NOT NULL,
    checked_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (routine_item_id, check_date)
);
