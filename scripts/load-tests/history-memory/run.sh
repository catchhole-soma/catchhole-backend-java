#!/usr/bin/env bash
set -euo pipefail
if [[ $# -lt 3 || $# -gt 5 ]]; then
  echo 'Usage: run.sh CHECKOUT OUTPUT baseline|candidate [SAMPLES] [serial|overlap]' >&2
  exit 2
fi
checkout=$(cd "$1" && pwd)
mkdir -p "$2"
output=$(cd "$2" && pwd)
revision_kind=$3
samples=${4:-3}
scenario=${5:-serial}
harness=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
[[ "$revision_kind" == baseline || "$revision_kind" == candidate ]] || exit 2
[[ "$samples" =~ ^[1-5]$ ]] || exit 2
[[ ! -f "$output/exit-code.txt" ]] || { echo 'Use a new output directory.' >&2; exit 2; }
[[ "$scenario" == serial || "$scenario" == overlap ]] || exit 2
export GH374_SCENARIO="$scenario"
export GH374_MEMORY_PROFILE=true
export GH374_MEASURE_BASELINE=false
[[ "$revision_kind" != baseline ]] || export GH374_MEASURE_BASELINE=true
export GH374_PERFORMANCE_OUTPUT="$output"
export GH374_SAMPLES="$samples"
git -C "$checkout" rev-parse HEAD > "$output/commit.txt"
git -C "$checkout" diff --stat > "$output/uncommitted-diff-stat.txt"
python3 - "$checkout" "$output" <<'PY'
import hashlib
import json
from pathlib import Path
import subprocess
import sys
checkout, output = map(Path, sys.argv[1:])
digest = hashlib.sha256()
for source in sorted((checkout / 'src/main').rglob('*')):
    if source.is_file():
        digest.update(str(source.relative_to(checkout)).encode() + b'\0' + source.read_bytes() + b'\0')
(output / 'source-revision.json').write_text(json.dumps({
    'mainSourceTreeSha256': digest.hexdigest(),
    'trackedDiffSha256': hashlib.sha256(subprocess.check_output(['git', '-C', str(checkout), 'diff', '--binary'])).hexdigest(),
}) + '\n')
PY
java -version > "$output/java-version.txt" 2>&1
docker info --format '{{.ServerVersion}} {{.MemTotal}}' > "$output/docker-environment.txt"
shasum -a 256 \
 "$checkout/src/test/java/org/monitoring/catchholebackend/domain/analysis/service/AnalysisHistoryMemoryPostgresIntegrationTest.java" \
 "$checkout/src/test/java/org/monitoring/catchholebackend/support/JdbcResultMetrics.java" \
 "$harness/heap.init.gradle" > "$output/harness.sha256"
printf '%q ' "$0" "$@" > "$output/command.txt"
printf '\n' >> "$output/command.txt"
cd "$checkout"
set +e
./gradlew -I "$harness/heap.init.gradle" test --tests '*AnalysisHistoryMemoryPostgresIntegrationTest' \
 --no-daemon --max-workers=1 --console=plain > "$output/gradle.log" 2>&1
result=$?
set -e
printf '%s\n' "$result" > "$output/exit-code.txt"
for report in build/test-results/test/TEST-*AnalysisHistoryMemoryPostgresIntegrationTest.xml; do
 [[ ! -f "$report" ]] || cp "$report" "$output/test-result.xml"
done
echo "GH374 $revision_kind finished with exit $result. Artifacts: $output"
exit "$result"
