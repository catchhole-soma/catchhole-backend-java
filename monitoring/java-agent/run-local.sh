#!/usr/bin/env bash
set -euo pipefail
task_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
task_root="$(cd -- "$task_dir/../.." && pwd)"
task_version="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$task_dir/agent.lock.json")"
task_agent="$task_dir/cache/opentelemetry-javaagent-$task_version.jar"
task_extension="$task_dir/build/libs/catchhole-tracing-privacy.jar"

if [[ $# -lt 1 ]]; then
  echo 'Usage: run-local.sh boot-run [Gradle options] | classpath CLASSPATH MAIN_CLASS [arguments] | APPLICATION.jar [arguments]' >&2
  exit 2
fi
if [[ ! -f "$task_agent" || ! -f "$task_extension" ]]; then
  echo 'Run monitoring/java-agent/setup-local.sh first.' >&2
  exit 2
fi
python3 - "$task_dir" <<'PY'
import hashlib, json, pathlib, sys
directory = pathlib.Path(sys.argv[1])
lock = json.loads((directory / 'agent.lock.json').read_text())
agent = directory / 'cache' / f"opentelemetry-javaagent-{lock['version']}.jar"
if hashlib.sha256(agent.read_bytes()).hexdigest() != lock['sha256']:
    raise SystemExit('Agent checksum mismatch; application was not started.')
PY

# Do not apply the agent to Gradle itself through JAVA_TOOL_OPTIONS.
if [[ "$1" == boot-run ]]; then
  shift
  exec "$task_root/gradlew" --no-daemon -p "$task_root" \
    -I "$task_dir/boot-run.init.gradle" \
    "-Dcatchhole.tracing.directory=$task_dir" bootRun "$@"
fi
if [[ "$1" == classpath ]]; then
  shift
  if [[ $# -lt 2 ]]; then
    echo 'classpath mode requires CLASSPATH and MAIN_CLASS.' >&2
    exit 2
  fi
  task_classpath="$1"
  shift
  exec java "-javaagent:$task_agent" \
    "-Dotel.javaagent.extensions=$task_extension" \
    "-Dotel.javaagent.configuration-file=$task_dir/agent-local.properties" \
    -cp "$task_classpath" "$@"
fi
task_application="$1"
shift
exec java "-javaagent:$task_agent" \
  "-Dotel.javaagent.extensions=$task_extension" \
  "-Dotel.javaagent.configuration-file=$task_dir/agent-local.properties" \
  -jar "$task_application" "$@"
