-- 기타 정보(CARE INFO): 생활 환경, 알레르기·자극 경험 (Figma "3-4 기타정보")

CREATE TABLE service_environments (
    user_id            BIGINT      PRIMARY KEY REFERENCES users (id),
    wash_time          VARCHAR(30) NOT NULL, -- MORNING_EVENING, EVENING_ONLY, VARIES, NO_FIXED_TIME
    outdoor_training   VARCHAR(30) NOT NULL, -- ALMOST_DAILY, TWO_TO_THREE_PER_WEEK, WEEKLY_OR_LESS, IRREGULAR
    allergy_experience VARCHAR(10) NOT NULL, -- YES, NO, UNKNOWN
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 알레르기·자극 경험이 있는 제품 또는 성분 (조합 규칙 R01의 입력)
CREATE TABLE skin_alerts (
    id         BIGSERIAL    PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users (id),
    target     VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (user_id, target)
);
