"""Exercise production startup gates without starting Spring or calling AWS."""

from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "deploy/backend-entrypoint.sh"


def main():
    with tempfile.TemporaryDirectory(prefix="catchhole-entrypoint-") as directory:
        folder = Path(directory)
        # A fake java executable observes argv; there is no shell eval or app I/O.
        executable = folder / "java"
        executable.write_text('#!/bin/sh\nprintf "%s\\n" "$@" > "$ARGV_FILE"\n')
        executable.chmod(0o755)
        argv = folder / "argv"
        base = {
            "PATH": str(folder) + os.pathsep + os.defpath,
            "ARGV_FILE": str(argv),
            "JAVA_OPTS": "-Xmx768m -Duser.timezone=Asia/Seoul",
        }
        disabled = subprocess.run(
            ["sh", str(SCRIPT), "--example"], env=base, capture_output=True, text=True,
            timeout=5, check=True,
        )
        assert not disabled.stdout and not disabled.stderr
        assert argv.read_text().splitlines() == [
            "-Xmx768m", "-Duser.timezone=Asia/Seoul", "-jar", "/app/app.jar", "--example",
        ]
        argv.unlink()
        invalid = [
            "", "http://8.8.8.8:4318/v1/traces", "http://0.0.0.0:4318/v1/traces",
            "http://127.0.0.1:4318/v1/traces", "http://169.254.169.254:4318/v1/traces",
            "http://10.0.0.256:4318/v1/traces", "http://010.0.0.1:4318/v1/traces",
            "http://172.15.0.1:4318/v1/traces", "http://172.32.0.1:4318/v1/traces",
            "http://user:secret@10.0.0.1:4318/v1/traces",
            "http://10.0.0.1:4318/v1/traces?secret=canary",
            "http://10.0.0.1:4318/v1/traces#canary", "http://10.0.0.1:4317/v1/traces",
            "http://10.0.0.1:4318/v1/traces\nhttp://10.0.0.2:4318/v1/traces",
        ]
        for endpoint in invalid:
            result = subprocess.run(["sh", str(SCRIPT)], env={
                **base, "BACKEND_TRACING_ENABLED": "true", "BACKEND_TRACING_OTLP_ENDPOINT": endpoint,
            }, capture_output=True, text=True, timeout=5)
            assert result.returncode == 2 and "requires http://" in result.stderr
            assert not argv.exists(), "Invalid tracing config started Java"
            assert endpoint == "" or endpoint not in result.stderr
        result = subprocess.run(["sh", str(SCRIPT)], env={
            **base, "BACKEND_TRACING_ENABLED": "tru",
        }, capture_output=True, text=True, timeout=5)
        assert result.returncode == 2 and not argv.exists()
        # On the host no /app artifacts are installed: a valid private address
        # passes address validation, then fails closed at the mandatory bundle.
        if not Path("/app/otel/opentelemetry-javaagent.jar").exists():
            for endpoint in (
                "http://10.0.0.1:4318/v1/traces", "http://172.16.0.1:4318/v1/traces",
                "http://172.31.255.254:4318/v1/traces", "http://192.168.0.1:4318/v1/traces",
            ):
                result = subprocess.run(["sh", str(SCRIPT)], env={
                    **base, "BACKEND_TRACING_ENABLED": "true", "BACKEND_TRACING_OTLP_ENDPOINT": endpoint,
                }, capture_output=True, text=True, timeout=5)
                assert result.returncode == 2 and "bundled tracing artifact" in result.stderr
                assert not argv.exists()
        print(json.dumps({"passed": True, "disabled_argv_preserved": True,
                          "invalid_endpoints_rejected": len(invalid), "unknown_flag_rejected": True}))


if __name__ == "__main__":
    main()
