# 자체 AI 모델 연동 (AI API v6.3.2)

원본: "제대로(jedero) AI API 백엔드 연동 규격 및 규칙서 v6.3.2"(PDF, 6쪽, 2026-11-26, Model A 5.0.0 / Model B 6.3.2). 아래는 우리 백엔드가 쓰는 부분만 정리한 것이다.
원본 PDF에는 API 키가 평문으로 들어 있으니 레포·문서에 올리지 않는다.

**v4.2 → v6.3에서 바뀐 것**: 주소(`junseong2im00--…` 새 작업 공간)와 키 교체 / `/chat/stream`의 `delta`가 마크다운, `final.markdown` 추가(저장 기준) / `tool_call`·`tool_result` 이벤트 추가 / `/routine` 단계 분할(1~9일 2단계, 10일 이상 3단계)과 `*_steps[].step_type` 추가 / `/analyze`에 `capture_quality`·`vision_readout`·`revisit_summary` 추가 / 컨테이너 최대 2개.

## 접속

| 모델 | 주소(환경변수) | 엔드포인트 |
|---|---|---|
| Model A 분석·리포트·3D | `JEDERO_MODEL_A_URL` | `POST /analyze` |
| Model B 루틴·Q&A | `JEDERO_MODEL_B_URL` | `POST /routine`, `POST /chat`, `POST /chat/stream` (SSE) |

- 헤더: `Content-Type: application/json; charset=utf-8`, `X-API-Key: <키>`(또는 `Authorization: Bearer <키>`) (`JEDERO_MODEL_A_API_KEY`, `JEDERO_MODEL_B_API_KEY`). `GET /health`는 인증 없음.
- 코드: `skin.opp.backend.ai.JederoClient`, 설정 `app.ai.*` (`AiProperties`).

## 요청

### `/analyze` (Model A)
- 사진: JSON 안 Base64. `image_base64`(1장) 또는 `images_base64`(최대 13장, 우선). `data:image/jpeg;base64,...`와 순수 Base64 모두 허용, JPEG/PNG/WEBP. 사진 없이 문진만 보내도 된다.
- `concerns[]`: `kind`(9종), `regions`, `onset`, `duration`, `severity`(정수 1~5, 벗어나면 400), `change`
  - `kind`: `acne_comedonal_inflammatory`, `post_acne_scar_erythema_pores`, `contact_irritant_dermatitis`, `dryness_barrier_damage`, `folliculitis_shaving`, `uv_pigmentation_photoaging`, `atopic_dermatitis`, `seborrheic_dermatitis`, `skin_improvement_maintenance`
  - `onset`: `immediate_acute` | `training_field` | `during_service` | `post_product_shave` | `chronic_pre_enlistment` | `other_unspecified`
  - `duration`: `acute_days` | `weeks` | `months` | `service_ongoing` | `chronic_years`
  - `change`: `worsened` | `improved` | `no_effect`(또는 `unchanged`)
- `traits`(모두 선택): `survey_answers`(바우만 원시 문진 응답), `oil_water_tendency`, `sensitivity_reactions`, `recurring_patterns`, `allergies_conditions_medication`, `region_types`(부위 → `dry`/`oily`)
- `military`(9칸, 모두 선택): `training`, `duty`, `washing`, `product_limits`, `unit_climate`, `irritants`, `care_time`, `hygiene_product_access`, `medical_access`
- 우리 쪽(`ConsultService`, 문진 상담): 문진은 선택지로 받아 `concerns[0]`을 그대로 채운다(값은 소문자, 부위는 대문자). `traits.allergies_conditions_medication` = 주의 성분, `military.training` = 고른 활동 + 생활 환경, `irritants` = 방독면·위장크림·헬멧 턱끈·면도, `care_time`, `washing`, `hygiene_product_access`. 위험 신호가 있으면 AI를 부르지 않는다.
- 확인 필요: `traits.survey_answers`의 키(예시 `q1_tight`, `q2_oil`…)가 우리 설문 25문항 키와 다르다. 키 목록을 받기 전까지 보내지 않는다.

### `/chat`, `/chat/stream` (Model B)
- `user_message`(또는 `message`): 필수, 비면 400
- `analysis_report`(선택): 직전 `/analyze`의 `cause_solution_report` 그대로
- `survey`(선택): `concerns`, `traits.baumann`(확정 유형 4글자, 예 `OSNT`), `traits.allergies`, `military`
- `history`(선택): `[{role: user|assistant, content}]`, 서버가 최근 6개만 쓴다
- 우리 쪽(`ChatService`): `traits.baumann` = 최신 설문 결과 앞 4글자, `traits.allergies` = 주의 성분, `military.training`·`washing` = 생활 환경, `history` = 최근 6개(안전 검사로 바뀐 답변 제외)

### `/routine` (Model B)
- `target_days`: 0·생략이면 `general_routine`(1단계 "매일 반복"), 1~9면 `period_plan` 2단계, 10~365면 3단계(예: 14일 → 1~4, 5~9, 10~14일차). "D-3 이내 신제품·각질제거 금지"는 마지막 단계에만 나온다
- `goal_event`(선택), `analysis_report`, `survey`는 `/chat`과 같다.

## 응답

- `/chat`: `chat_response` = `reply`, `action_tips[]`, `follow_up_questions[]`, `evidence_ids[]`, `refer_to_clinician`, `notice`, `referral_notice`
- `/chat/stream`: SSE `meta` → (`tool_call` → `tool_result`, 선택) → `delta`(`{text, format: "markdown"}`, 여러 번) → `final`(`replace_reply`, `markdown`, `chat_response`, `tool_trace`, `internal_audit`).
  - `final.markdown`은 실천 팁까지 담긴 전체 답변(마크다운). `chat_response.reply`는 앞뒤 문장만, 팁은 `action_tips`로 따로 온다.
  - 우리는 `final.markdown`을 답변으로 저장하고 메시지에 `format: "markdown"`을 붙인다(없으면 예전처럼 `reply`, `format: "text"`). 실측: delta는 빈 줄이 빠져 오고 `replace_reply: true`라 화면은 done의 내용으로 바꾼다.
  - `tool_call`·`tool_result`·`meta`는 프론트로 넘기지 않는다.
  - 맥락: "저장된 피부 정보 사용"이 켜져 있으면 `survey`(바우만, 주의 성분, 복무 환경)와 함께, 최근 30일 안의 가장 최근 상담이 있으면 그 `cause_solution_report`를 `analysis_report`로, 그 상담의 고민을 `survey.concerns`로 보낸다(`ProfileContext.latestReport`).
  - 우리 쪽 가드레일(`ChatGuard`, 규칙 기반): 프롬프트 인젝션·위급 신호(자해 등) 질문은 AI를 부르지 않고 정해진 안내로 답한다(호출 횟수 미차감, 질문·답 모두 BLOCKED라 맥락에서 빠짐, 답 `payload.guard`에 사유). 전화번호·주민번호·이메일·군번·계좌번호는 가려서 보낸다(저장은 원문). 답변에 진단 단정·처방·전문의약품 사용 권유·지시문 노출이 있으면 `BLOCKED_REPLY`로 바꾼다(`payload.guard_output`). 피부와 무관한 질문의 범위 제한은 모델 쪽 지시문에서 할 일.
  - 요청에 `attachments`(최대 4개, 각 800자), `enable_tools`(기본 true)를 쓸 수 있다(아직 안 씀).
- `/routine`: `routine_plan` = `mode`, `target_days`, `summary`, `phases[]`(`phase_label`, `day_range`, `focus`, `morning[]`, `evening[]`, `post_activity[]`, `checkpoint`), `evidence_ids`, `refer_to_clinician`, `notice`, `referral_notice`
  - v6.3: 각 단계에 같은 순서의 `morning_steps[]`·`evening_steps[]`·`post_activity_steps[]`(`{text, step_type}`)가 함께 온다. `step_type` 10종: `cleanser`, `toner`, `serum`, `moisturizer`, `sunscreen`, `spot_care`, `shaving_care`, `lip_hand_care`, `mask_pack`(PX 제품 카드 연결용), `habit`(제품 아님). 아직 안 씀.
- `/analyze`: `cause_solution_report`(`cause_solution_pairs[]`: `regions`, `state_observation`, `cause_summary`, `solution_action`, `confidence`, `evidence_ids`), `skin3d_payload`(부위 6개 × 채널 6개, `global_profile.baumann_axes`), `ingredient_profile`(`baumann_inferred`, `efficacy_weights`, `recommended_ingredients[]`, `caution_ingredients[]`; v5에 `regional_profiles`, `ingredient_conflict_alerts`, `px_matching_vector` 추가)
  - v5 추가: `capture_quality`(사진을 보낼 때, `retake_recommended`면 재촬영 안내), `vision_readout`, `revisit_summary`(요청에 이전 `skin3d_payload`를 `previous_skin3d_payload`로 보내면 부위별 호전·악화 요약). 우리는 사진·재방문 비교를 아직 안 쓴다
  - 부위: `FOREHEAD`, `CHEEK_LEFT`, `CHEEK_RIGHT`, `NOSE`, `CHIN`, `EYE_AREA` (본인 기준 좌우, 항상 6개)
  - 채널(0~1): `redness`, `spot`, `line`, `pore_density`, `roughness`, `melanin`
- 모든 응답의 `internal_audit`(내부 근거 원문, `gate_violations`)은 **밖으로 내보내지 않는다**.

## 오류·재시도·시간

| HTTP | `error.code` | 처리 (`JederoClient`) |
|---|---|---|
| 400 | `INVALID_INPUT` | 재시도 안 함 |
| 401 | `UNAUTHORIZED` | 재시도 안 함 |
| 422 | `VALIDATION_ERROR` | 재시도 안 함 |
| 500 | `INTERNAL_ERROR` | 재시도 안 함 (명세 권장은 1회 재시도) |
| 502/503/504 | (게이트웨이) | 지수 백오프 재시도 최대 2회 (1초, 2초) |
| 200 | `gate_violations` 있음 | Model B: 결과를 쓰지 않는다(채팅은 의료기관 안내 문구로 바꿔 `BLOCKED` 저장). Model A: 위반 pair가 빠진 결과를 쓴다 |

- 오류 본문: `{"detail": "...", "error": {"code": "...", "message": "..."}}`
- 응답 시간(v6.3 명세): warm `/analyze` 8~14초, `/routine` 8~12초(14일 18~26초), `/chat` 3.5~6초, `/chat/stream` 첫 글자 0.5~1.2초·전체 6~14초. 60초 호출이 없으면 내려가고 콜드 스타트는 A 15~25초, B 45~65초. 실측(2026-11-26): `/health` 콜드 A 35초, B 77초.
- 명세 권장 대기: `/analyze` 60초, `/routine` 75초, `/chat` 45초, `/chat/stream` 90초.
- 우리 설정: connect 5초, 응답 대기 90초, 스트리밍 전체 150초. 서버 nginx `proxy_read_timeout`은 310초.
- 동시 처리: 모델별 컨테이너 최대 2개 (A는 컨테이너당 1건, B는 8건). 넘으면 모델 쪽 큐에서 기다린다.
- 워밍업(피크 시간 150초마다 `GET /health`)은 GPU 비용이 늘어 아직 켜지 않았다.

## 제품 궁합 점수 (`MatchScorer`, `GET /api/products/recommendations`)

`MatchScore = clip(50 + 30·S_baumann + 20·S_concern − P_allergen, 0, 99)`
- `S_baumann`(−0.5~1.0): 바우만 4축 권장·주의 성분 일치도
- `S_concern`(0~1): `ingredient_profile`·고민 유형 적합도
- `P_allergen`: 등록한 알레르기 성분(INCI 정확 일치) 포함 시 60. 바우만 유형이 없으면 점수 없음(null)
- 성분 유사도: 전성분을 표준 INCI + 효능 8종 벡터로 바꾸고, 앞 순서 가중치 `1/log2(i+2)`로 `efficacy_weights`와 가중 코사인 유사도

우리 구현(`skin.opp.backend.product`):
- S_baumann: 성분 규칙 시트(`src/main/resources/rules/ingredient_rules.csv`, docs/rules 사본)의 사용자 유형 글자별 도움·주의 성분 중 **INCI가 있는 "성분" 규칙만** 제품 주요 성분과 맞춘다. 도움 가중치 합 1.5 = 1, 주의 합 1.0 = 1, 차이를 −0.5~1로 자른다. "그룹"(에센셜 오일류 등)은 그룹 정의가 없어 아직 반영하지 않는다.
- S_concern: 효능 8종 벡터 대신 리포트 `recommended_ingredients`(INCI·score)와 제품 성분(순서 가중치)의 가중 코사인. 리포트 `caution_ingredients` 중 INCI로 맞출 수 있는 키(향료·변성알코올·설페이트)가 들어 있으면 절반.
- 제품 성분은 `product_ingredients`(INCI, sort_order)만 쓴다. 표기 통일: D-판테놀→판테놀, Parfum→Fragrance 등.
- 조합 규칙 R03~R08(대체·제한·분리)은 제품 점수가 아니라 루틴·안내 규칙이라 점수에 넣지 않았다.
