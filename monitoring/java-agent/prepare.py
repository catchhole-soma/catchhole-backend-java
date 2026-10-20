"""Fetch only the locked official agent and verify it before atomic installation."""

import hashlib
import json
import tempfile
import urllib.request
from pathlib import Path


def prepare() -> None:
    directory = Path(__file__).resolve().parent
    lock = json.loads((directory / "agent.lock.json").read_text())
    cache = directory / "cache"
    cache.mkdir(exist_ok=True)
    target = cache / f"opentelemetry-javaagent-{lock['version']}.jar"
    if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() == lock["sha256"]:
        print(f"Verified Java agent {lock['version']}: {target}")
        return

    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=cache, delete=False) as output:
            temporary = Path(output.name)
            digest = hashlib.sha256()
            with urllib.request.urlopen(lock["url"], timeout=30) as response:
                while chunk := response.read(1024 * 1024):
                    digest.update(chunk)
                    output.write(chunk)
        if digest.hexdigest() != lock["sha256"]:
            raise SystemExit("Agent checksum mismatch; the download was not installed.")
        temporary.chmod(0o644)
        temporary.replace(target)
        print(f"Verified Java agent {lock['version']}: {target}")
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    prepare()
