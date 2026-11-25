-- 자체 PX 제품 DB와 내 보유제품 (Figma "4-1제품추천", "제품상세", "제품상세-성분", "내 보유제품")
-- 제품 데이터는 스프레드시트 일괄 등록(양식 미정)으로 채운다. 이 마이그레이션은 구조만 만든다.

CREATE TABLE products (
    id                  BIGSERIAL    PRIMARY KEY,
    sheet_key           VARCHAR(50)  UNIQUE,          -- 스프레드시트 행 ID
    brand               VARCHAR(50)  NOT NULL,
    name                VARCHAR(100) NOT NULL,
    category            VARCHAR(20)  NOT NULL,        -- CLEANSER, TONER, LOTION, ESSENCE, CREAM, SUNSCREEN
    volume              VARCHAR(30),                  -- 예: 150ml
    price               INT          NOT NULL CHECK (price >= 0),
    summary             VARCHAR(200),                 -- 한 줄 설명
    recommendation      VARCHAR(300),                 -- "이런 분께 추천해요"
    skin_fits           TEXT[]       NOT NULL DEFAULT '{}', -- OILY, DRY, COMBINATION, SENSITIVE (필터 "피부 특성")
    features            TEXT[]       NOT NULL DEFAULT '{}', -- 예: 저자극, 피지케어
    full_ingredients    TEXT,                         -- 전성분
    image_url           VARCHAR(1000),
    px_available        BOOLEAN      NOT NULL DEFAULT TRUE,
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX products_category_idx ON products (category) WHERE active;

-- 주요 성분 (상세의 "주요 성분", 성분 규칙과 INCI로 맞춘다)
CREATE TABLE product_ingredients (
    product_id BIGINT       NOT NULL REFERENCES products (id),
    sort_order INT          NOT NULL,
    name_ko    VARCHAR(100) NOT NULL,
    inci       VARCHAR(150),
    PRIMARY KEY (product_id, sort_order)
);

-- 내 보유제품: 구매한 제품(OWNED) / 관심 제품(INTEREST)
CREATE TABLE user_products (
    id         BIGSERIAL   PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id),
    product_id BIGINT      NOT NULL REFERENCES products (id),
    kind       VARCHAR(10) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, product_id, kind)
);
