#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 4 || $# -gt 5 ]]; then
  echo 'Usage: run.sh CHECKOUT OUTPUT legacy|compact baseline|candidate [SAMPLES]' >&2
  exit 2
fi
checkout=$(cd "$1" && pwd)
mkdir -p "$2"
output=$(cd "$2" && pwd)
mode=$3
revision_kind=$4
samples=${5:-3}
harness=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
[[ "$mode" == legacy || "$mode" == compact ]] || exit 2
[[ "$revision_kind" == baseline || "$revision_kind" == candidate ]] || exit 2
[[ "$samples" =~ ^([1-9]|10)$ ]] || exit 2
[[ "$mode" != compact || "$revision_kind" != baseline ]] || exit 2

export GH365_MEMORY_PROFILE=true
export GH365_JOURNAL_MODE="$mode"
export GH365_MEASURE_BASELINE=false
[[ "$revision_kind" != baseline ]] || export GH365_MEASURE_BASELINE=true
export GH365_PERFORMANCE_OUTPUT="$output"
export GH365_SAMPLES="$samples"
git -C "$checkout" rev-parse HEAD > "$output/commit.txt"
git -C "$checkout" diff --stat > "$output/uncommitted-diff-stat.txt"
java -version > "$output/java-version.txt" 2>&1
docker info --format '{{.ServerVersion}} {{.MemTotal}}' > "$output/docker-environment.txt"
shasum -a 256 \
  "$checkout/src/test/java/org/monitoring/catchholebackend/domain/worldsetting/service/WorldComparisonMemoryPostgresIntegrationTest.java" \
  "$checkout/src/test/java/org/monitoring/catchholebackend/support/JdbcResultMetrics.java" \
  "$harness/heap.init.gradle" > "$output/harness.sha256"
printf '%q ' "$0" "$@" > "$output/command.txt"
printf '\n' >> "$output/command.txt"
cd "$checkout"
set +e
./gradlew -I "$harness/heap.init.gradle" test \
  --tests '*WorldComparisonMemoryPostgresIntegrationTest' \
  --no-daemon --max-workers=1 --console=plain > "$output/gradle.log" 2>&1
result=$?
set -e
printf '%s\n' "$result" > "$output/exit-code.txt"
# Gradle shortens long report filenames with a hash; match the stable class suffix.
for report in build/test-results/test/TEST-*WorldComparisonMemoryPostgresIntegrationTest.xml; do
  [[ ! -f "$report" ]] || cp "$report" "$output/test-result.xml"
done
echo "GH365 $revision_kind/$mode finished with exit $result. Artifacts: $output"
exit "$result"
