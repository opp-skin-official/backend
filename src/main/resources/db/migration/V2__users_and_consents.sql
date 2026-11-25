-- 사용자 (카카오 로그인)
CREATE TABLE users (
    id                BIGSERIAL PRIMARY KEY,
    kakao_id          BIGINT       NOT NULL UNIQUE,
    nickname          VARCHAR(100),
    email             VARCHAR(255),
    profile_image_url VARCHAR(1000),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at        TIMESTAMPTZ
);

-- 동의 기록 (약관, 개인정보, 피부 프로필, 민감정보, 얼굴 촬영)
CREATE TABLE consents (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id),
    type       VARCHAR(40) NOT NULL,
    agreed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ
);

CREATE INDEX consents_user_id_idx ON consents (user_id);
