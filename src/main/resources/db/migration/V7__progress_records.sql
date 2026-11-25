-- 경과 기록 (IA U-05 경과 기록 작성, "경과 기록 기준"; Figma "루틴-관리 경과"의 피부 컨디션)
CREATE TABLE progress_records (
    id               BIGSERIAL    PRIMARY KEY,
    user_id          BIGINT       NOT NULL REFERENCES users (id),
    record_date      DATE         NOT NULL,
    perceived_change VARCHAR(10)  NOT NULL, -- BETTER, SAME, WORSE, UNSURE
    skin_condition   VARCHAR(15)  NOT NULL, -- COMFORT, NORMAL, UNCOMFORTABLE
    memo             VARCHAR(500),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (user_id, record_date)
);
