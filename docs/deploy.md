# 배포 가이드

## 구성

```
사용자 ──https──▶ (프론트 도메인, Cloudflare Workers, Next.js)
          └─https──▶ api.opp.skin (EC2 t4g·ARM 3.24.204.31, 시드니 ap-southeast-2)
                       nginx (Let's Encrypt, 이미 설치됨)
                         └─▶ 127.0.0.1:8080 백엔드 컨테이너 ─▶ RDS PostgreSQL 17 (자동 백업)
```

- `api.opp.skin`의 DNS는 Cloudflare에서 서버 IP를 직접 가리킨다(프록시 꺼짐). 인증서는 서버 nginx가 갖고 있다(Let's Encrypt, 2027-02-23 만료, 갱신 확인 필요).
- 백엔드는 서버의 Docker Compose로 띄운다(`deploy/compose.yaml`). `127.0.0.1:8080`에만 열려 nginx만 접근한다.
- DB는 **RDS PostgreSQL 17**. 접속 정보는 `.env`의 `DB_URL`·`DB_USERNAME`·`DB_PASSWORD`(없으면 compose가 시작을 거부). RDS 보안 그룹은 EC2 보안 그룹에서 오는 5432만 허용하고 퍼블릭 접근은 끈다. `DB_URL` 예: `jdbc:postgresql://<RDS 엔드포인트>:5432/oppskin?sslmode=require`
- RDS 없이 서버 안 컨테이너 DB로 돌릴 때만 `COMPOSE_PROFILES=local-db`와 `DB_URL=jdbc:postgresql://db:5432/oppskin`.
- 이미지는 `linux/amd64`·`linux/arm64` 멀티 플랫폼이라 t4g(ARM)에서 그대로 받는다.
- 세션·CSRF 쿠키는 상위 도메인 `opp.skin`으로 내려 프론트와 `api.opp.skin`이 함께 쓴다. 그래서 **프론트도 `opp.skin` 아래 주소**(예: `opp.skin`, `app.opp.skin`)여야 로그인이 동작한다.

## 서버에 처음 한 번 할 일 (SSH)

1. Docker와 Compose 플러그인 설치
2. `/opt/oppskin`에 `deploy/compose.yaml`, `deploy/deploy.sh`, `deploy/.env.example` 복사
3. `.env.example`을 `.env`로 복사해 값을 채우고 `chmod 600 .env`
4. GHCR 로그인 (이미지가 비공개일 때): `read:packages` 권한 토큰으로 `docker login ghcr.io`
5. nginx의 `api.opp.skin` 443 server 블록에 `deploy/nginx-api.opp.skin.conf`의 `location /`를 넣고 `nginx -t && systemctl reload nginx`
6. `./deploy.sh` 실행 → 헬스 체크 통과 메시지 확인. 빈 RDS에는 이때 Flyway가 테이블(V1~)을 만든다.
7. PX 제품 데이터는 **6번 뒤에** `products`·`product_ingredients`에 옮긴다(테이블이 먼저 있어야 함). 추천 점수에는 `product_ingredients.inci`(영문 성분명)가 필요하다.

## 배포 (이후)

1. 백엔드 PR을 `main`에 머지 → GitHub Actions `Publish image`가 `ghcr.io/opp-skin-official/backend:latest`, `:sha-<커밋>` 이미지를 올림
2. 서버에서 `cd /opt/oppskin && ./deploy.sh` (특정 버전은 `./deploy.sh sha-<커밋>`)
3. 시작할 때 Flyway가 DB 마이그레이션을 적용한다.

## 백엔드 환경변수 (`/opt/oppskin/.env`)

| 이름 | 값 | 비밀 | 설명 |
|---|---|---|---|
| `DB_URL` | `jdbc:postgresql://<RDS 엔드포인트>:5432/oppskin?sslmode=require` | | RDS 접속 주소 |
| `DB_USERNAME` | | | RDS 사용자 |
| `DB_PASSWORD` | | ✓ | RDS 비밀번호 |
| `FRONTEND_URL` | 예: `https://opp.skin` | | 로그인 후 이동할 주소 (**미정**) |
| `CORS_ALLOWED_ORIGINS` | 예: `https://opp.skin` | | 쉼표로 여러 개 |
| `COOKIE_DOMAIN` | `opp.skin` | | 쿠키 공유 범위 |
| `KAKAO_CLIENT_ID` | | ✓ | REST API 키 |
| `KAKAO_CLIENT_SECRET` | | ✓ | 카카오 로그인 클라이언트 시크릿 |
| `JEDERO_MODEL_A_URL`, `JEDERO_MODEL_B_URL` | | | 자체 AI 모델 주소 |
| `JEDERO_MODEL_A_API_KEY`, `JEDERO_MODEL_B_API_KEY` | | ✓ | |
| `AI_LIMIT_CHAT_PER_DAY`, `AI_LIMIT_CONSULT_PER_DAY` | | | 사용자별 AI 호출 제한(최근 24시간). 기본 질문 50·상담 10, 0이면 제한 없음. 넘으면 429 |

prod 프로필(이미지 기본값)에서 바뀌는 것: 쿠키 `Secure`, 쿠키 도메인 = `COOKIE_DOMAIN`, 프록시 헤더(Tomcat RemoteIpValve, 사설 IP에서 온 것만) 신뢰, API 문서 끔.

## 프론트 (Cloudflare Workers)

- 레포 `opp-skin-official/frontend`를 `@opennextjs/cloudflare`로 Workers(`opp-skin-frontend`)에 배포한다. 자세한 방법은 프론트 README "배포".
- 빌드 변수 `NEXT_PUBLIC_API_BASE_URL=https://api.opp.skin` (빌드 때 들어감)
- 도메인을 `opp.skin` 아래로 연결 (대시보드 Workers > 도메인, DNS가 Cloudflare라 자동)

## 카카오 콘솔

- REST API 키 > 카카오 로그인 리다이렉트 URI에 `https://api.opp.skin/login/oauth2/code/kakao` 추가 (로컬 주소는 유지)

## 확인한 것 (로컬에서 서버 구성 재현)

- 이미지 빌드, 이미지 안에 `.env` 없음, 실행 사용자 `app`(uid 1001)
- `deploy/compose.yaml`로 백엔드 + PostgreSQL 실행 → 마이그레이션 9개 적용, `127.0.0.1:8080/actuator/health` UP, DB 외부 포트 없음
- `DB_PASSWORD` 없이 실행하면 compose가 거부
- nginx(자체 서명 인증서) + `deploy/nginx-api.opp.skin.conf` 설정으로 HTTPS 요청:
  - `redirect_uri=https://api.opp.skin/login/oauth2/code/kakao`
  - `XSRF-TOKEN`·`SESSION` 쿠키 `Domain=opp.skin; Secure; SameSite=Lax`, 세션은 `HttpOnly`
  - `https://opp.skin` CORS 허용(credentials), 비로그인 `/api/me` 401

## 남은 결정·주의

- **프론트 도메인**: `opp.skin`, `app.opp.skin` 모두 DNS 레코드가 아직 없음
- **서버 리전**: 시드니(ap-southeast-2). 국내 사용자에게는 서울(ap-northeast-2)보다 응답이 느릴 수 있음
- **DB 백업**: RDS 자동 백업 사용 (보존 기간은 RDS 설정에서 확인)
- **사진 저장소(S3)**: 리뷰·피부 사진 업로드 작업 때 버킷과 권한(EC2 역할) 추가
