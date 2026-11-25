-- 제품 리뷰 (Figma "리뷰작성", "보유제품 등록", "제품상세-성분"의 전체리뷰). 사용자당 제품별 1건.
CREATE TABLE product_reviews (
    id          BIGSERIAL    PRIMARY KEY,
    product_id  BIGINT       NOT NULL REFERENCES products (id),
    user_id     BIGINT       NOT NULL REFERENCES users (id),
    rating      SMALLINT     NOT NULL CHECK (rating BETWEEN 1 AND 5),
    content     VARCHAR(500) NOT NULL,
    skin_option VARCHAR(20),                         -- 구매 옵션(선택): OILY, DRY, OILY_DRY, SENSITIVE, COMBINATION
    good_tags   TEXT[]       NOT NULL DEFAULT '{}',
    bad_tags    TEXT[]       NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (product_id, user_id)
);

CREATE INDEX product_reviews_product_idx ON product_reviews (product_id, created_at DESC);
