#!/usr/bin/env bash
set -euo pipefail

# 이 테스트는 갱신 실패 뒤 reload 실행, 설정 검사 누락, 실패 코드 유실을 잡는다.
# Docker 실행만 대체하고 실제 갱신 스크립트의 분기·종료 코드를 검증한다.
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
test_dir="$(mktemp -d "${TMPDIR:-/tmp}/catchhole-renew-test.XXXXXX")"
test_dir="$(cd "$test_dir" && pwd -P)"
trap 'rm -rf "$test_dir"' EXIT
mkdir -p "$test_dir/bin" "$test_dir/deploy/monitoring" "$test_dir/elsewhere"
if [[ -f "$script_dir/../renew-certificate.sh" ]]; then
  cp "$script_dir/../renew-certificate.sh" "$test_dir/deploy/monitoring/renew-certificate.sh"
fi

cat > "$test_dir/bin/docker" <<'DOCKER'
#!/usr/bin/env bash
set -euo pipefail
[[ "$PWD" == "$EXPECTED_DEPLOY_DIR" ]] || exit 90
[[ "$1" == compose && "$2" == --env-file && "$3" == monitoring.env && "$4" == -f && "$5" == compose.monitoring.prod.yml ]] || exit 91
shift 5
case "$*" in
  'run --rm --no-deps certbot renew --cert-name monitoring.catchhole.com --non-interactive --no-random-sleep-on-renew')
    printf 'renew\n' >> "$COMMAND_LOG"
    exit "$RENEW_STATUS"
    ;;
  'exec -T nginx nginx -t')
    printf 'check\n' >> "$COMMAND_LOG"
    exit "$CHECK_STATUS"
    ;;
  'exec -T nginx nginx -s reload')
    printf 'reload\n' >> "$COMMAND_LOG"
    exit "$RELOAD_STATUS"
    ;;
  *) exit 92 ;;
esac
DOCKER
chmod +x "$test_dir/bin/docker"

failures=0
run_case() {
  local label="$1" renew_status="$2" check_status="$3" reload_status="$4" expected_status="$5" expected_commands="$6"
  local actual_status actual_commands
  : > "$test_dir/commands"
  if (
    cd "$test_dir/elsewhere"
    PATH="$test_dir/bin:$PATH" \
      EXPECTED_DEPLOY_DIR="$test_dir/deploy" \
      COMMAND_LOG="$test_dir/commands" \
      RENEW_STATUS="$renew_status" CHECK_STATUS="$check_status" RELOAD_STATUS="$reload_status" \
      bash "$test_dir/deploy/monitoring/renew-certificate.sh"
  ) > "$test_dir/output" 2>&1; then
    actual_status=0
  else
    actual_status=$?
  fi
  actual_commands="$(cat "$test_dir/commands")"
  if [[ "$actual_status" != "$expected_status" || "$actual_commands" != "$expected_commands" ]]; then
    printf 'FAIL %s: expected status %s and commands [%s], got status %s and commands [%s]\n' \
      "$label" "$expected_status" "$expected_commands" "$actual_status" "$actual_commands"
    failures=$((failures + 1))
  else
    printf 'PASS %s\n' "$label"
  fi
}

run_case 'renewal failure stops before nginx' 17 0 0 17 'renew'
run_case 'invalid nginx config stops before reload' 0 23 0 23 $'renew\ncheck'
run_case 'reload failure remains a failure' 0 0 31 31 $'renew\ncheck\nreload'
run_case 'success checks config before reload from the deploy directory' 0 0 0 0 $'renew\ncheck\nreload'

[[ "$failures" == 0 ]]
