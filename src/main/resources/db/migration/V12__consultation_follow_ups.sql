-- 후속 상담(C-01 후속): "지난번과 달라진 점"에서 시간·물품만 달라졌을 때,
-- 이전 상담의 분석을 그대로 쓰고 루틴만 다시 만든 새 상담이 이전 상담을 가리킨다.
ALTER TABLE conversations ADD COLUMN follows_id BIGINT REFERENCES conversations (id);
