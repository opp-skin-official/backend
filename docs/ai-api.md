# 자체 AI 모델 연동 (AI API v4.2)

원본: "제대로(jedero) AI API v4.2.0 백엔드 연동 명세서"(PDF, 6쪽). 아래는 우리 백엔드가 쓰는 부분만 정리한 것이다.

## 접속

| 모델 | 주소(환경변수) | 엔드포인트 |
|---|---|---|
| Model A 분석·리포트·3D | `JEDERO_MODEL_A_URL` | `POST /analyze` |
| Model B 루틴·Q&A | `JEDERO_MODEL_B_URL` | `POST /routine`, `POST /chat`, `POST /chat/stream` (SSE) |

- 헤더: `Content-Type: application/json; charset=utf-8`, `X-API-Key: <키>` (`JEDERO_MODEL_A_API_KEY`, `JEDERO_MODEL_B_API_KEY`). `GET /health`는 인증 없음.
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
- `target_days`: 1~365면 `period_plan`(2~3단계 기간 루틴), 0·생략이면 `general_routine`
- `goal_event`(선택), `analysis_report`, `survey`는 `/chat`과 같다.

## 응답

- `/chat`: `chat_response` = `reply`, `action_tips[]`, `follow_up_questions[]`, `evidence_ids[]`, `refer_to_clinician`, `notice`, `referral_notice`
- `/chat/stream`: SSE `meta` → `delta`(`{text}`, 여러 번) → `final`(`replace_reply: true` + `/chat`과 같은 본문). **delta는 띄어쓰기가 빠질 수 있어** 화면은 final의 `reply`로 바꾼다.
- `/routine`: `routine_plan` = `mode`, `target_days`, `summary`, `phases[]`(`phase_label`, `day_range`, `focus`, `morning[]`, `evening[]`, `post_activity[]`, `checkpoint`), `evidence_ids`, `refer_to_clinician`, `notice`, `referral_notice`
- `/analyze`: `cause_solution_report`(`cause_solution_pairs[]`: `regions`, `state_observation`, `cause_summary`, `solution_action`, `confidence`, `evidence_ids`), `skin3d_payload`(부위 6개 × 채널 6개, `global_profile.baumann_axes`), `ingredient_profile`(`baumann_inferred`, `efficacy_weights`, `recommended_ingredients[]`, `caution_ingredients[]`)
  - 부위: `FOREHEAD`, `CHEEK_LEFT`, `CHEEK_RIGHT`, `NOSE`, `CHIN`, `EYE_AREA` (본인 기준 좌우, 항상 6개)
  - 채널(0~1): `redness`, `spot`, `line`, `pore_density`, `roughness`, `melanin`
- 모든 응답의 `internal_audit`(내부 근거 원문, `gate_violations`)은 **밖으로 내보내지 않는다**.

## 오류·재시도·시간

| HTTP | `error.code` | 처리 (`JederoClient`) |
|---|---|---|
| 400 | `INVALID_INPUT` | 재시도 안 함 |
| 401 | `UNAUTHORIZED` | 재시도 안 함 |
| 422 | `VALIDATION_ERROR` | 재시도 안 함 |
| 500 | `INTERNAL_ERROR` | 재시도 안 함 |
| 502/503/504 | (게이트웨이) | 지수 백오프 재시도 최대 2회 (1초, 2초) |
| 200 | `gate_violations` 있음 | Model B: 결과를 쓰지 않는다(채팅은 의료기관 안내 문구로 바꿔 `BLOCKED` 저장). Model A: 위반 pair가 빠진 결과를 쓴다 |

- 오류 본문: `{"detail": "...", "error": {"code": "...", "message": "..."}}`
- 응답 시간: warm `/chat` 4~8초, `/analyze` 5~13초, `/routine` 8~16초, 콜드 스타트 25~45초(3분 호출이 없으면 내려감). 실측: 콜드 약 22초, warm 약 5~10초(2026-11-26).
- 우리 설정: connect 5초, 응답 대기 90초, 스트리밍 전체 150초. 서버 nginx `proxy_read_timeout`은 310초.
- 동시 처리: 모델별 컨테이너 최대 5개 × 1건. 넘으면 모델 쪽 큐에서 기다린다.
- 워밍업(피크 시간 150초마다 `GET /health`)은 GPU 비용이 늘어 아직 켜지 않았다.

## 제품 궁합 점수 (제품 추천 때 사용 예정)

`MatchScore = clip(50 + 30·S_baumann + 20·S_concern − P_allergen, 0, 99)`
- `S_baumann`(−0.5~1.0): 바우만 4축 권장·주의 성분 일치도
- `S_concern`(0~1): `ingredient_profile`·고민 유형 적합도
- `P_allergen`: 등록한 알레르기 성분(INCI 정확 일치) 포함 시 60. 바우만 유형이 없으면 점수 없음(null)
- 성분 유사도: 전성분을 표준 INCI + 효능 8종 벡터로 바꾸고, 앞 순서 가중치 `1/log2(i+2)`로 `efficacy_weights`와 가중 코사인 유사도
