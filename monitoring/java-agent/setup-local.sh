#!/usr/bin/env bash
set -euo pipefail
task_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
task_root="$(cd -- "$task_dir/../.." && pwd)"
python3 "$task_dir/prepare.py"
"$task_root/gradlew" --no-daemon -p "$task_dir" test jar
