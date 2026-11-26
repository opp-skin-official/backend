#!/usr/bin/env bash
# 운영 서버에서 실행: 새 백엔드 이미지를 받아 다시 띄우고, 헬스 체크가 통과할 때까지 기다린다.
# 사용: cd /opt/oppskin && ./deploy.sh [이미지 태그, 기본 latest]
set -euo pipefail
cd "$(dirname "$0")"

TAG="${1:-latest}"
export BACKEND_IMAGE="ghcr.io/opp-skin-official/backend:${TAG}"

if [ ! -f .env ]; then
  echo ".env 가 없습니다. .env.example 을 복사해 값을 채워 주세요." >&2
  exit 1
fi

docker compose pull backend
docker compose up -d

echo "헬스 체크 대기 중..."
for i in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"'; then
    echo "배포 완료: ${BACKEND_IMAGE}"
    exit 0
  fi
  sleep 3
done

echo "헬스 체크 실패. 최근 로그:" >&2
docker compose logs --tail=100 backend >&2
exit 1
