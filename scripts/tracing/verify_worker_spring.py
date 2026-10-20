"""Opt-in local Python -> Spring/JDBC -> OTLP -> Tempo verification.

Run with the AI checkout's virtualenv. Uses a test-only Spring controller/H2
fixture, never the application's configuration, database or cloud providers.
Only synthetic data is submitted. Credentials are read only for localhost
Grafana; they are never printed or included in the result artifact.
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import gzip
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4

REPO = Path(__file__).resolve().parents[2]
CANARIES = (b"GH271_SQL_CANARY", b"gh271-error-canary", b"gh271-api-key-canary",
            b"gh271-query-canary", b"gh271-delay-canary")
JAVA_SERVICE = "catchhole-backend-tracing-verification"
PYTHON_SERVICE = "catchhole-ai-worker-stage5-check"
JAVA_ATTRIBUTE_KEYS = {
    "http.request.method", "http.route", "http.response.status_code",
    "db.system.name", "db.operation.name",
}


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def http_json(url, *, headers=None):
    with urlopen(Request(url, headers=headers or {}), timeout=5) as response:
        return json.load(response)


class CaptureServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self):
        super().__init__(("127.0.0.1", 0), CaptureHandler)
        self.lock = threading.Lock()
        self.payloads = []
        self.forward_failures = 0
        self.rejected_payloads = 0

    def snapshot(self):
        with self.lock:
            return list(self.payloads)


class CaptureHandler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def do_POST(self):
        if self.path != "/v1/traces":
            self.send_error(404)
            return
        try:
            size = int(self.headers.get("Content-Length", "0"))
            require(0 < size < 8 * 1024 * 1024, "Invalid synthetic OTLP payload size")
            body = self.rfile.read(size)
            if self.headers.get("Content-Encoding") == "gzip":
                body = gzip.decompress(body)
            # Inspect pre-Collector data. Never forward raw canaries to Tempo.
            require(not any(marker in body for marker in CANARIES),
                    "Private canary found before Collector")
            with self.server.lock:
                self.server.payloads.append(body)
            request = Request("http://127.0.0.1:4318/v1/traces", data=body,
                              headers={"Content-Type": "application/x-protobuf"})
            with urlopen(request, timeout=5) as response:
                require(response.status == 200, "Collector rejected synthetic trace")
        except (OSError, ValueError, RuntimeError, EOFError):
            with self.server.lock:
                if "body" in locals() and any(marker in body for marker in CANARIES):
                    self.server.rejected_payloads += 1
                else:
                    self.server.forward_failures += 1
            self.send_response(500)
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/x-protobuf")
        self.send_header("Content-Length", "0")
        self.end_headers()


def captured_spans(payloads):
    from opentelemetry.proto.collector.trace.v1.trace_service_pb2 import (
        ExportTraceServiceRequest,
    )

    result = {}
    for payload in payloads:
        message = ExportTraceServiceRequest.FromString(payload)
        for resource in message.resource_spans:
            attrs = {a.key: a.value.string_value for a in resource.resource.attributes}
            for scope in resource.scope_spans:
                for span in scope.spans:
                    if attrs.get("service.name") == JAVA_SERVICE:
                        require(set(attrs) == {"service.name", "deployment.environment.name"},
                                "Unexpected Java resource attributes before Collector")
                        require({a.key for a in span.attributes} <= JAVA_ATTRIBUTE_KEYS,
                                "Unexpected Java span attributes before Collector")
                        require(not span.events and not span.status.message,
                                "Java exported events or free-form status text")
                        require(not scope.scope.attributes,
                                "Java exported instrumentation scope attributes")
                        require(not span.trace_state and all(not link.attributes for link in span.links),
                                "Java exported trace state or link attributes")
                    result[span.span_id.hex()] = {
                        "trace_id": span.trace_id.hex(), "span_id": span.span_id.hex(),
                        "parent_id": span.parent_span_id.hex(), "name": span.name,
                        "service": attrs.get("service.name"), "kind": span.kind,
                        "status": span.status.code,
                        "start": span.start_time_unix_nano, "end": span.end_time_unix_nano,
                        "attributes": {a.key: a.value for a in span.attributes},
                    }
    return result


def grafana_auth(env_path):
    for line in env_path.read_text().splitlines():
        key, sep, value = line.partition("=")
        if sep and key.strip() == "LOCAL_GRAFANA_ADMIN_PASSWORD":
            password = value.strip().strip("\"'")
            require(bool(password), "Local Grafana password is empty")
            return "Basic " + base64.b64encode(("admin:" + password).encode()).decode()
    raise RuntimeError("Local Grafana password was not found")


def read_tempo(trace_id, auth, expected_ids):
    url = ("http://127.0.0.1:3001/api/datasources/proxy/uid/catchhole-tempo"
           f"/api/traces/{trace_id}")
    for _ in range(40):
        try:
            body = http_json(url, headers={"Authorization": auth, "Accept": "application/json"})
            spans = [span for batch in body.get("batches", [])
                     for scope in batch.get("scopeSpans", []) for span in scope.get("spans", [])]
            ids = {base64.b64decode(s["spanId"]).hex() for s in spans}
            if ids == expected_ids:
                return body, spans
        except HTTPError as exc:
            if exc.code != 404:
                raise RuntimeError(f"Local Grafana trace readback HTTP {exc.code}") from None
        time.sleep(0.5)
    raise RuntimeError("Tempo did not return all expected synthetic spans")


async def exercise(base_url, endpoint):
    import httpx
    from app.clients.spring_worker_client import SpringWorkerClient
    from app.core.config import Settings
    from app.monitoring.worker_tracing import (
        WorkerTracingRuntime,
        create_worker_tracing,
        set_process_tracing,
        worker_span,
    )
    from opentelemetry import trace
    from opentelemetry.sdk.resources import Resource
    from opentelemetry.sdk.trace import TracerProvider
    from opentelemetry.sdk.trace.sampling import ALWAYS_OFF, ParentBased

    settings = Settings(_env_file=None, app_env="local", ai_worker_tracing_enabled=True,
                        otel_service_name=PYTHON_SERVICE,
                        otel_exporter_otlp_traces_endpoint=endpoint)
    runtime = create_worker_tracing(settings, "analysis")
    require(runtime is not None, "Could not initialize isolated tracing runtime")
    previous = set_process_tracing(runtime)
    client = SpringWorkerClient(base_url, "gh271-api-key-canary",
                                http_client=httpx.AsyncClient(timeout=10, trust_env=False))
    result = {"sampled": [], "unsampled": None}

    async def reserve(model):
        request_id = uuid4()
        await client.reserve_ai_tokens(request_id, uuid4(), "SETTING_EXTRACTION", 1,
                                       model, 100, uuid4())
        return request_id

    async def successful_job():
        with worker_span("analysis.job.execute", root=True,
                         attributes={"analysis.job.id": str(uuid4())}):
            trace_id = format(trace.get_current_span().get_span_context().trace_id, "032x")
            request_id = await reserve("gh271-delay-canary")
            await client.settle_ai_tokens(request_id, 30, 0, 10, "SUCCESS")
        return {"scenario": "concurrent-reserve-settle", "trace_id": trace_id}

    try:
        # Executes real HTTP/JDBC work without a Worker root; no Java trace expected.
        await reserve("synthetic-unparented")
        result["sampled"].extend(await asyncio.gather(successful_job(), successful_job()))
        with worker_span("analysis.job.execute", root=True):
            trace_id = format(trace.get_current_span().get_span_context().trace_id, "032x")
            response = await client._request(
                "POST", base_url + "/api/internal/v1/ai-token-usages/reserve?canary=gh271-query-canary",
                headers={"X-Internal-Api-Key": "gh271-api-key-canary",
                         "X-Worker-Lease-Token": str(uuid4())},
                json={"requestId": str(uuid4()), "analysisJobId": str(uuid4()),
                      "purpose": "SETTING_EXTRACTION", "attempt": 1,
                      "modelName": "gh271-error-canary", "reservedTokens": 100},
            )
            require(response.status_code == 500, "Synthetic failure did not return HTTP 500")
        result["sampled"].append({"scenario": "controlled-http-500", "trace_id": trace_id})
        require(runtime.provider.force_flush(timeout_millis=5000), "Python flush timed out")

        off = WorkerTracingRuntime(TracerProvider(
            sampler=ParentBased(ALWAYS_OFF), shutdown_on_exit=False,
            resource=Resource({"service.name": "catchhole-unsampled-stage5-check"})))
        set_process_tracing(off)
        try:
            with worker_span("analysis.job.execute", root=True):
                ctx = trace.get_current_span().get_span_context()
                require(ctx.is_valid and not ctx.trace_flags.sampled,
                        "Unsampled test context is invalid")
                result["unsampled"] = format(ctx.trace_id, "032x")
                await reserve("synthetic-unsampled")
        finally:
            set_process_tracing(runtime)
            off.shutdown()
    finally:
        await client.aclose()
        set_process_tracing(previous)
        runtime.shutdown()
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ai-checkout", type=Path, required=True)
    parser.add_argument("--classpath", type=Path,
                        default=REPO / "build/tracing-verification/classpath.txt")
    parser.add_argument("--agent-jar", type=Path,
                        default=REPO / "monitoring/java-agent/cache/opentelemetry-javaagent-2.32.0.jar")
    parser.add_argument("--extension-jar", type=Path,
                        default=REPO / "monitoring/java-agent/build/libs/catchhole-tracing-privacy.jar")
    parser.add_argument("--grafana-env", type=Path,
                        default=REPO / "monitoring/.env.tracing.local")
    args = parser.parse_args()
    # This is a standalone verification process: discard ambient application,
    # exporter credentials and proxy settings before importing the AI packages.
    for key in list(os.environ):
        if key not in {"PATH", "HOME", "TMPDIR", "JAVA_HOME", "LANG"}:
            del os.environ[key]
    require((args.ai_checkout / "app/monitoring/worker_tracing.py").is_file(),
            "AI checkout does not contain Worker tracing")
    for path in (args.classpath, args.agent_jar, args.extension_jar, args.grafana_env):
        require(path.is_file(), "Required local verification artifact is missing: " + str(path))
    sys.path.insert(0, str(args.ai_checkout.resolve()))
    output = Path(tempfile.mkdtemp(prefix="catchhole-gh271-stage5-"))
    capture = CaptureServer()
    thread = threading.Thread(target=capture.serve_forever, daemon=True)
    thread.start()
    endpoint = f"http://127.0.0.1:{capture.server_port}/v1/traces"
    ready = output / "ready.json"
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin/java") if java_home else shutil.which("java")
    require(bool(java), "Java 21 is required")
    command = [java, "-Xmx512m", f"-javaagent:{args.agent_jar.resolve()}",
               f"-Dotel.javaagent.extensions={args.extension_jar.resolve()}",
               f"-Dotel.javaagent.configuration-file={REPO / 'monitoring/java-agent/agent-local.properties'}",
               f"-Dotel.exporter.otlp.traces.endpoint={endpoint}",
               "-Dotel.service.name=catchhole-backend-tracing-verification",
               "-cp", args.classpath.read_text().strip(),
               "org.monitoring.traceverification.TraceVerificationApplication",
               f"--trace.verification.ready-file={ready}"]
    # Do not inherit ambient OTEL/JAVA/Spring/database/credential configuration.
    child_env = {key: value for key, value in os.environ.items()
                 if key in {"PATH", "HOME", "TMPDIR", "JAVA_HOME", "LANG"}}
    process = None
    try:
        with (output / "fixture.log").open("w") as log:
            process = subprocess.Popen(command, cwd=output, env=child_env,
                                       stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 90
            while not ready.exists():
                require(process.poll() is None, "Synthetic Spring fixture exited; inspect its local log")
                require(time.monotonic() < deadline, "Synthetic Spring fixture did not become ready")
                time.sleep(0.2)
            port = json.loads(ready.read_text())["port"]
            scenarios = asyncio.run(exercise(f"http://127.0.0.1:{port}", endpoint))
            # Allow bounded Java batch processing; shutdown flushes remaining spans.
            process.terminate()
            process.wait(timeout=20)
        require(capture.rejected_payloads == 0, "Privacy canary reached the OTLP receiver")
        require(capture.forward_failures == 0, "Synthetic trace forwarding failed")
        spans = captured_spans(capture.snapshot())
        trace_ids = {s["trace_id"] for s in spans.values()}
        expected_trace_ids = {s["trace_id"] for s in scenarios["sampled"]}
        require(trace_ids == expected_trace_ids,
                "Unexpected unparented/unsampled trace or missing sampled trace")
        auth = grafana_auth(args.grafana_env)
        concurrent_intervals = []
        for scenario in scenarios["sampled"]:
            group = {sid: s for sid, s in spans.items() if s["trace_id"] == scenario["trace_id"]}
            servers = [s for s in group.values() if s["kind"] == 2]
            clients = [s for s in group.values() if s["kind"] == 3
                       and s["service"] == PYTHON_SERVICE and s["name"] == "spring.http.request"]
            expected_requests = 2 if scenario["scenario"] == "concurrent-reserve-settle" else 1
            require(len(servers) == expected_requests and len(clients) == expected_requests,
                    "Missing or duplicate HTTP CLIENT/SERVER spans")
            for server in servers:
                parent = group.get(server["parent_id"])
                require(server["service"] == JAVA_SERVICE and parent in clients,
                        "Java SERVER parent is not the Python CLIENT span")
            require({s["parent_id"] for s in servers} == {s["span_id"] for s in clients},
                    "Each Python CLIENT must have exactly one Java SERVER child")
            jdbc = [s for s in group.values() if s["kind"] == 3
                    and s["service"] == JAVA_SERVICE
                    and any(key.startswith("db.") for key in s["attributes"])]
            require(bool(jdbc), "No JDBC span was exported")
            jdbc_servers = set()
            for span in jdbc:
                cursor, seen = span, set()
                while cursor["kind"] != 2:
                    require(cursor["span_id"] not in seen, "Span parent cycle")
                    seen.add(cursor["span_id"])
                    cursor = group.get(cursor["parent_id"])
                    require(cursor is not None, "JDBC span has no SERVER ancestor")
                jdbc_servers.add(cursor["span_id"])
            require(jdbc_servers == {s["span_id"] for s in servers},
                    "Each HTTP request must include a JDBC descendant")
            if scenario["scenario"] == "controlled-http-500":
                require(servers[0]["status"] == 2 and clients[0]["status"] == 2,
                        "HTTP 500 lacks Python/Java error status")
            else:
                first_server = min(servers, key=lambda span: span["start"])
                concurrent_intervals.append((first_server["start"], first_server["end"]))
            body, remote = read_tempo(scenario["trace_id"], auth, set(group))
            for span in remote:
                local = group[base64.b64decode(span["spanId"]).hex()]
                parent_id = base64.b64decode(span.get("parentSpanId", "")).hex()
                require(parent_id == local["parent_id"], "Tempo changed a parent relationship")
            (output / f"{scenario['trace_id']}.json").write_text(json.dumps(body, indent=2))
            scenario.update(span_count=len(group), server_spans=len(servers), jdbc_spans=len(jdbc),
                            same_trace_and_parent_ids_verified=True)
        require(max(start for start, _ in concurrent_intervals)
                < min(end for _, end in concurrent_intervals),
                "Concurrent reservation requests did not overlap")
        summary = {"synthetic": True, "real_python_client": True, "real_java_controller": True,
                   "database": "isolated H2", "production_business_logic_verified": False,
                   "privacy_checked_before_collector": True, "tempo_readback_verified": True,
                   "concurrent_server_requests_overlap": True,
                   "unparented_and_unsampled_excluded": True, "scenarios": scenarios["sampled"]}
        (output / "verification.json").write_text(json.dumps(summary, indent=2))
        print(json.dumps({"output_directory": str(output), **summary}, indent=2))
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        capture.shutdown()
        capture.server_close()
        thread.join(timeout=5)


if __name__ == "__main__":
    main()
