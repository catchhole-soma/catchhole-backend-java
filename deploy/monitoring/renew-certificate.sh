#!/usr/bin/env bash
set -euo pipefail

deploy_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$deploy_dir"
compose=(docker compose --env-file monitoring.env -f compose.monitoring.prod.yml)

# 실패 코드가 systemd에 전달된다. 갱신·설정 검사 실패 뒤에는 reload하지 않는다.
"${compose[@]}" run --rm --no-deps certbot renew --cert-name monitoring.catchhole.com --non-interactive --no-random-sleep-on-renew
"${compose[@]}" exec -T nginx nginx -t
"${compose[@]}" exec -T nginx nginx -s reload

# renew의 성공은 갱신 대상이 없었던 경우도 포함한다.
printf '인증서 갱신 확인과 Nginx 설정 반영을 완료했습니다.\n'
