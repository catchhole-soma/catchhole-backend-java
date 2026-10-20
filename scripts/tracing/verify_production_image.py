"""Opt-in verification of the real production entrypoint/Agent bundle with synthetic H2.

Build the production Dockerfile image separately, then run this script using the
AI checkout's venv (OTLP protobuf is required). No application configuration,
production DB, AWS, paid LLM, existing Collector, or existing containers are used.
Only /app/app.jar is replaced for the ON/OFF cases; the missing-extension case
copies the image's Agent/config into a temporary bundle with the extension absent.
"""

from __future__ import annotations

import argparse
import gzip
import ipaddress
import json
import os
import subprocess
import sys
import tempfile
import threading
import time
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import ProxyHandler, Request, build_opener
from uuid import UUID, uuid4

REPO = Path(__file__).resolve().parent.parent.parent
CANARIES = (b"GH271_SQL_CANARY", b"gh271-error-canary", b"gh271-api-key-canary",
            b"gh271-query-canary", b"gh271-delay-canary")
ATTRIBUTES = {"http.request.method", "http.route", "http.response.status_code",
              "db.system.name", "db.operation.name"}


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def capture_server(directory):
    """Container mode uses Python stdlib only and never forwards telemetry."""
    folder = Path(directory)
    lock = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass

        def do_POST(self):
            if self.path != "/v1/traces":
                self.send_error(404)
                return
            size = int(self.headers.get("Content-Length", "0"))
            if not 0 < size < 8 * 1024 * 1024:
                self.send_error(413)
                return
            body = self.rfile.read(size)
            if self.headers.get("Content-Encoding") == "gzip":
                body = gzip.decompress(body)
            with lock:
                (folder / f"{uuid4().hex}.bin").write_bytes(body)
            self.send_response(200)
            self.send_header("Content-Length", "0")
            self.end_headers()

    ThreadingHTTPServer(("0.0.0.0", 4318), Handler).serve_forever()


def docker(*args, timeout=30, check=True):
    environment = {key: value for key, value in os.environ.items() if key in {
        "PATH", "HOME", "DOCKER_HOST", "DOCKER_CONFIG", "DOCKER_CONTEXT", "XDG_RUNTIME_DIR", "LANG",
    }}
    result = subprocess.run(
        ["docker", *args], env=environment, capture_output=True, text=True,
        timeout=timeout, check=False,
    )
    if check:
        require(result.returncode == 0, "Isolated Docker verification command failed.")
    return result


def reserve(base, *, trace_id=None, sampled=True, model="synthetic", parent_id=None):
    body = {
        "requestId": str(uuid4()), "analysisJobId": str(uuid4()), "purpose": "SETTING_EXTRACTION",
        "attempt": 1, "modelName": model, "reservedTokens": 100,
    }
    headers = {"Content-Type": "application/json", "X-Internal-Api-Key": "gh271-api-key-canary",
               "X-Worker-Lease-Token": str(uuid4())}
    if trace_id is not None:
        headers["traceparent"] = f"00-{trace_id}-{parent_id}-{'01' if sampled else '00'}"
    request = Request(
        base + "/api/internal/v1/ai-token-usages/reserve?canary=gh271-query-canary",
        data=json.dumps(body).encode(), headers=headers,
    )
    opener = build_opener(ProxyHandler({}))
    try:
        with opener.open(request, timeout=10) as response:
            return response.status
    except HTTPError as error:
        return error.code


def container_reserve(receiver, base, **options):
    # An internal network has no host-published ports. Execute only the synthetic
    # HTTP client in the receiver container; the image under test stays unchanged.
    result = docker("exec", receiver, "python", "/verify.py", "--request",
                    json.dumps({"base": base, **options}), timeout=15)
    return int(result.stdout.strip())


def wait_ready(name, output):
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        state = json.loads(docker("inspect", name).stdout)[0]["State"]
        require(state["Running"], "Synthetic image fixture exited before readiness.")
        if docker("exec", name, "test", "-f", "/tmp/trace-ready.json", check=False).returncode == 0:
            network = next(iter(json.loads(docker("inspect", name).stdout)[0]
                                ["NetworkSettings"]["Networks"].values()))
            return "http://" + network["IPAddress"] + ":8080"
        time.sleep(0.25)
    (output / f"{name}.log").write_text(docker("logs", name, check=False).stdout)
    raise RuntimeError("Synthetic image fixture readiness timed out.")


def inspect_payloads(folder):
    from opentelemetry.proto.collector.trace.v1.trace_service_pb2 import (
        ExportTraceServiceRequest,
    )

    spans = {}
    instances = set()
    for payload in folder.glob("*.bin"):
        body = payload.read_bytes()
        require(not any(canary in body for canary in CANARIES),
                "Privacy canary was exported by the actual production image.")
        message = ExportTraceServiceRequest.FromString(body)
        for batch in message.resource_spans:
            resource = {item.key: item.value.string_value for item in batch.resource.attributes}
            require(set(resource) == {"service.name", "deployment.environment.name", "service.instance.id"},
                    "Unexpected production resource attribute.")
            require(resource["service.name"] == "catchhole-backend"
                    and resource["deployment.environment.name"] == "prod",
                    "Actual production entrypoint did not apply production resources.")
            instances.add(str(UUID(resource["service.instance.id"])))
            for scoped in batch.scope_spans:
                require(not scoped.scope.attributes, "Instrumentation scope exported attributes.")
                for span in scoped.spans:
                    keys = {attribute.key for attribute in span.attributes}
                    require(keys <= ATTRIBUTES, "Production Java Span exported unapproved attributes.")
                    require(not span.events and not span.status.message and not span.trace_state
                            and all(not link.attributes for link in span.links),
                            "Production Span exported an event or free-form metadata.")
                    spans[span.span_id.hex()] = {
                        "trace_id": span.trace_id.hex(), "span_id": span.span_id.hex(),
                        "parent_span_id": span.parent_span_id.hex(), "name": span.name,
                        "kind": span.kind, "status": span.status.code, "attribute_keys": sorted(keys),
                        "start_unix_nano": span.start_time_unix_nano,
                        "end_unix_nano": span.end_time_unix_nano,
                    }
    return spans, instances


def verify_fixture_jar(path):
    with zipfile.ZipFile(path) as jar:
        names = jar.namelist()
        require("BOOT-INF/classes/org/monitoring/traceverification/TraceVerificationApplication.class" in names,
                "The explicit verification BootJar is required.")
        require(not any(name.startswith("BOOT-INF/classes/") and (
            name.endswith((".yml", ".yaml", ".properties")) or "/.env" in name
        ) for name in names), "Fixture JAR includes application configuration.")
        manifest = jar.read("META-INF/MANIFEST.MF").decode().replace("\r\n ", "").replace("\n ", "")
        require("Start-Class: org.monitoring.traceverification.TraceVerificationApplication" in manifest,
                "Fixture JAR start class is not isolated.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", default="catchhole-backend-tracing:stage7-local")
    parser.add_argument("--receiver-image", default="python:3.12-slim")
    parser.add_argument("--fixture-jar", type=Path,
                        default=REPO / "build/tracing-verification/trace-verification.jar")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    require(not args.output.exists(), "Output must be a new evidence file.")
    verify_fixture_jar(args.fixture_jar)
    inspected = json.loads(docker("image", "inspect", args.image).stdout)[0]
    require(inspected["Config"]["Entrypoint"] == ["/app/backend-entrypoint.sh"],
            "Image does not use the production entrypoint.")
    image_id = inspected["Id"]
    receiver_id = json.loads(docker("image", "inspect", args.receiver_image).stdout)[0]["Id"]
    owned = []
    network = "gh271-image-" + uuid4().hex[:12]
    network_created = False
    output = Path(tempfile.mkdtemp(prefix="catchhole-gh271-prod-image-"))
    captures = output / "captures"
    captures.mkdir()
    empty_extension = output / "missing-extension-bundle"
    empty_extension.mkdir()
    try:
        docker("network", "create", "--internal", network)
        network_created = True
        receiver = network + "-receiver"
        owned.append(receiver)
        docker("run", "-d", "--pull=never", "--name", receiver, "--network", network,
               "--memory=64m", "--read-only", "--tmpfs", "/tmp",
               "--mount", f"type=bind,src={Path(__file__).resolve()},dst=/verify.py,readonly",
               "--mount", f"type=bind,src={captures},dst=/captures",
               receiver_id, "python", "/verify.py", "--capture", "/captures")
        receiver_ip = next(iter(json.loads(docker("inspect", receiver).stdout)[0]
                                ["NetworkSettings"]["Networks"].values()))["IPAddress"]
        require(ipaddress.ip_address(receiver_ip).is_private, "Receiver network is not private.")
        endpoint = f"http://{receiver_ip}:4318/v1/traces"
        scenarios = []
        expected = {}

        for enabled in (True, False):
            name = network + ("-on" if enabled else "-off")
            owned.append(name)
            docker("run", "-d", "--pull=never", "--name", name, "--network", network,
                   "--memory=768m", "--read-only", "--tmpfs", "/tmp",
                   "--mount", f"type=bind,src={args.fixture_jar.resolve()},dst=/app/app.jar,readonly",
                   "-e", f"BACKEND_TRACING_ENABLED={str(enabled).lower()}",
                   "-e", f"BACKEND_TRACING_OTLP_ENDPOINT={endpoint}", "-e", "JAVA_OPTS=-Xmx256m",
                   image_id, "--trace.verification.ready-file=/tmp/trace-ready.json",
                   "--trace.verification.port=8080", "--trace.verification.address=0.0.0.0")
            base = wait_ready(name, output)
            command = docker("exec", name, "cat", "/proc/1/cmdline").stdout
            require(("-javaagent:" in command) == enabled, "ON/OFF entrypoint JVM argv mismatch.")
            before = len(list(captures.glob("*.bin")))
            if enabled:
                for scenario, model, status in (
                    ("sampled-success", "gh271-delay-canary", 200),
                    ("sampled-http-500", "gh271-error-canary", 500),
                ):
                    trace_id, parent_id = uuid4().hex, uuid4().hex[:16]
                    require(container_reserve(receiver, base, trace_id=trace_id,
                                              parent_id=parent_id, model=model) == status,
                            "Synthetic HTTP status mismatch.")
                    expected[trace_id] = {"scenario": scenario, "parent_id": parent_id, "status": status}
            require(container_reserve(receiver, base) == 200, "Unparented synthetic request failed.")
            require(container_reserve(receiver, base, trace_id=uuid4().hex, parent_id=uuid4().hex[:16],
                            sampled=False) == 200, "Unsampled synthetic request failed.")
            if not enabled:
                require(container_reserve(receiver, base, trace_id=uuid4().hex,
                                          parent_id=uuid4().hex[:16]) == 200,
                        "OFF synthetic sampled request failed.")
            docker("stop", "--time", "25", name, timeout=35)
            log = docker("logs", name, check=False)
            (output / f"{'on' if enabled else 'off'}.log").write_text(log.stdout + log.stderr)
            if not enabled:
                require(len(list(captures.glob("*.bin"))) == before, "OFF image exported telemetry.")
            scenarios.append({"tracing_enabled": enabled, "http_verified": True,
                              "javaagent_loaded": enabled})

        missing = network + "-missing-extension"
        for filename in ("opentelemetry-javaagent.jar", "agent-prod.properties"):
            docker("cp", f"{network}-on:/app/otel/{filename}", str(empty_extension / filename))
        owned.append(missing)
        docker("run", "-d", "--pull=never", "--name", missing, "--network", network,
               "--mount", f"type=bind,src={args.fixture_jar.resolve()},dst=/app/app.jar,readonly",
               "--mount", f"type=bind,src={empty_extension},dst=/app/otel,readonly",
               "-e", "BACKEND_TRACING_ENABLED=true", "-e", f"BACKEND_TRACING_OTLP_ENDPOINT={endpoint}",
               image_id, "--trace.verification.ready-file=/tmp/trace-ready.json")
        exited = docker("wait", missing, timeout=15).stdout.strip()
        require(exited == "2", "Missing privacy artifact did not reject startup.")
        spans, instances = inspect_payloads(captures)
        require({span["trace_id"] for span in spans.values()} == set(expected),
                "Unparented/unsampled traces were exported or sampled traces were lost.")
        require(len(instances) == 1, "Production process UUID was not stable for one process.")
        traces = []
        for trace_id, case in expected.items():
            group = {key: value for key, value in spans.items() if value["trace_id"] == trace_id}
            servers = [span for span in group.values() if span["kind"] == 2]
            jdbc = [span for span in group.values()
                    if span["kind"] == 3 and "db.system.name" in span["attribute_keys"]]
            require(len(servers) == 1 and servers[0]["parent_span_id"] == case["parent_id"],
                    "SERVER did not inherit the supplied W3C parent.")
            require(bool(jdbc) and all(span["parent_span_id"] == servers[0]["span_id"] for span in jdbc),
                    "JDBC did not attach to the HTTP SERVER.")
            require((servers[0]["status"] == 2) == (case["status"] == 500), "SERVER error status mismatch.")
            traces.append({"scenario": case["scenario"], "trace_id": trace_id,
                           "server_spans": len(servers), "jdbc_spans": len(jdbc),
                           "spans": list(group.values())})
        report = {
            "passed": True, "synthetic": True, "production_image_id": image_id,
            "receiver_image_id": receiver_id, "actual_image_entrypoint": True,
            "bundled_agent_extension_and_prod_config": True, "database": "isolated H2",
            "production_business_logic_verified": False, "tempo_readback_verified": False,
            "private_network_capture_only": True, "prod_and_process_uuid_verified": True,
            "privacy_checked_before_collector": True, "unsampled_and_unparented_excluded": True,
            "missing_privacy_artifact_rejected": True, "off_no_agent_or_export": True,
            "span_count": len(spans), "trace_count": len(traces), "traces": traces,
            "scenarios": scenarios, "local_artifact_directory": str(output),
        }
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("x", encoding="utf-8") as destination:
            json.dump(report, destination, indent=2)
        print(json.dumps({"passed": True, "trace_count": len(traces), "span_count": len(spans),
                          "output": str(args.output.resolve())}))
    finally:
        for name in reversed(owned):
            docker("rm", "-f", name, timeout=30, check=False)
        if network_created:
            docker("network", "rm", network, timeout=30, check=False)


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "--capture":
        capture_server(sys.argv[2])
    elif len(sys.argv) == 3 and sys.argv[1] == "--request":
        print(reserve(**json.loads(sys.argv[2])))
    else:
        main()
