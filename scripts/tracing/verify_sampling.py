"""Synthetic Head/Tail sampling lab, isolated from all application services.

Use the AI checkout's venv for its existing OTel/protobuf dependencies.
Starts one short-lived Collector with ephemeral loopback ports and no cloud
exporter. It records synthetic OTLP output in a fresh private temp directory.
Does not alter the running Collector, Tempo, Grafana, or application sampler.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import tempfile
import time
from pathlib import Path
from urllib.error import URLError
from urllib.request import ProxyHandler, Request, build_opener
from uuid import uuid4

from opentelemetry.proto.collector.trace.v1.trace_service_pb2 import (
    ExportTraceServiceRequest,
    ExportTraceServiceResponse,
)
from opentelemetry.proto.trace.v1.trace_pb2 import Span, Status
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
from opentelemetry.sdk.trace.id_generator import IdGenerator
from opentelemetry.sdk.trace.sampling import ParentBased, TraceIdRatioBased
from opentelemetry.trace import Status as ApiStatus
from opentelemetry.trace import StatusCode
from prometheus_client.parser import text_string_to_metric_families

REPO = Path(__file__).resolve().parents[2]
IMAGE = "otel/opentelemetry-collector-contrib:0.162.0"
HTTP = build_opener(ProxyHandler({}))


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def docker(*args, check=True):
    return subprocess.run(
        ["docker", *args], check=check, capture_output=True, text=True, timeout=30,
    ).stdout.strip()


class StratifiedIds(IdGenerator):
    """16 deterministic lower-64-bit strata; not a random traffic simulation."""

    def __init__(self, prefix):
        self.prefix = prefix
        self.trace_index = 0
        self.span_index = 0

    def generate_trace_id(self):
        result = (self.prefix << 64) | (self.trace_index << 60) | 1
        self.trace_index += 1
        return result

    def generate_span_id(self):
        self.span_index += 1
        return self.span_index


def head_trial(ratio, prefix):
    exporter = InMemorySpanExporter()
    provider = TracerProvider(
        resource=Resource({"service.name": "catchhole-stage6-head-lab"}),
        sampler=ParentBased(TraceIdRatioBased(ratio)),
        id_generator=StratifiedIds(prefix),
        shutdown_on_exit=False,
    )
    provider.add_span_processor(SimpleSpanProcessor(exporter))
    tracer = provider.get_tracer("sampling-lab")
    decisions = []
    business_completions = 0
    try:
        for _ in range(16):
            with tracer.start_as_current_span("synthetic.job") as root:
                root_context = root.get_span_context()
                with tracer.start_as_current_span("synthetic.late-error") as child:
                    child_context = child.get_span_context()
                    # ERROR is discovered after Head has already made its decision.
                    child.set_status(ApiStatus(StatusCode.ERROR))
                    require(child_context.trace_id == root_context.trace_id, "Lost trace context")
                    require(child_context.trace_flags.sampled == root_context.trace_flags.sampled,
                            "Child re-sampled its parent's decision")
                business_completions += 1
                decisions.append({
                    "trace_id": f"{root_context.trace_id:032x}",
                    "sampled": root_context.trace_flags.sampled,
                })
        spans = exporter.get_finished_spans()
        sampled = sum(item["sampled"] for item in decisions)
        require(len(spans) == sampled * 2, "Unexpected exported Head span count")
        require(business_completions == 16, "Sampling changed synthetic execution")
        return {
            "ratio": ratio, "business_completions": business_completions,
            "sampled_traces": sampled, "exported_spans": len(spans), "decisions": decisions,
            "errors_created_after_head_decision": 16,
        }
    finally:
        provider.shutdown()


def verify_head():
    prefix = uuid4().int >> 64
    trials = [head_trial(ratio, prefix) for ratio in (0.0, 0.25, 1.0)]
    repeated = head_trial(0.25, prefix)
    require([t["sampled_traces"] for t in trials] == [0, 4, 16],
            "Installed Python sampler changed the deterministic fixture's decision")
    require(trials[1]["decisions"] == repeated["decisions"], "Head decisions not reproducible")
    return {
        "fixture": "16 stratified Python TraceIdRatioBased IDs; not a random percentage guarantee",
        "repeat_decisions_identical": True, "trials": trials,
    }


def get_metrics(port):
    with HTTP.open(f"http://127.0.0.1:{port}/metrics", timeout=2) as response:
        return response.read().decode()


def metric_value(text, prefix, **labels):
    return sum(
        sample.value
        for family in text_string_to_metric_families(text)
        for sample in family.samples
        if sample.name in {prefix, prefix + "_total"}
        and all(sample.labels.get(k) == v for k, v in labels.items())
    )


def wait_until(predicate, message, timeout=15):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            value = predicate()
            if value:
                return value
        except (URLError, OSError, json.JSONDecodeError):
            pass
        time.sleep(0.1)
    raise RuntimeError(message)


def fixture_span(name, duration_ms, *, error=False, trace_id=None, span_id=None, parent=b""):
    now = time.time_ns()
    return Span(
        trace_id=trace_id or uuid4().bytes,
        span_id=span_id or uuid4().bytes[:8],
        parent_span_id=parent,
        name=name,
        kind=Span.SPAN_KIND_INTERNAL,
        start_time_unix_nano=now - int(duration_ms * 1_000_000),
        end_time_unix_nano=now,
        flags=1,
        status=Status(code=Status.STATUS_CODE_ERROR if error else Status.STATUS_CODE_OK),
    )


def send(port, spans):
    message = ExportTraceServiceRequest()
    resource = message.resource_spans.add()
    resource.resource.attributes.add(
        key="service.name", value={"string_value": "catchhole-stage6-tail-lab"},
    )
    scope = resource.scope_spans.add()
    scope.scope.name = "synthetic-sampling-lab"
    scope.spans.extend(spans)
    request = Request(
        f"http://127.0.0.1:{port}/v1/traces", data=message.SerializeToString(),
        headers={"Content-Type": "application/x-protobuf"},
    )
    with HTTP.open(request, timeout=3) as response:
        require(response.status == 200, "Lab Collector rejected a fixture")
        body = ExportTraceServiceResponse.FromString(response.read())
        require(body.partial_success.rejected_spans == 0, "Lab Collector partially rejected data")


def output_spans(path):
    if not path.exists():
        return []
    result = []
    for line in path.read_text().splitlines():
        body = json.loads(line)
        for resource in body.get("resourceSpans", []):
            for scope in resource.get("scopeSpans", []):
                result.extend(scope.get("spans", []))
    return result


def port_for(name, container_port):
    address = docker("port", name, f"{container_port}/tcp")
    require(address.startswith("127.0.0.1:"), "Lab port is not loopback-bound")
    return int(address.rsplit(":", 1)[1])


def verify_tail(output):
    name = "catchhole-stage6-sampling-" + uuid4().hex[:10]
    fixture = REPO / "monitoring/otel-collector.sampling-lab.yml"
    mounted = output / "collector"
    mounted.mkdir()
    # Never inherit application's OTEL or credential environment; Docker receives
    # only the following explicit flags and mounts.
    try:
        docker(
            "run", "--rm", "-d", "--name", name, "--memory", "128m", "--cpus", "0.5",
            "--user", f"{os.getuid()}:{os.getgid()}",
            "-p", "127.0.0.1::4318", "-p", "127.0.0.1::8888",
            "--mount", f"type=bind,source={fixture},target=/etc/otelcol/config.yml,readonly",
            "--mount", f"type=bind,source={mounted},target=/out",
            IMAGE, "--config=/etc/otelcol/config.yml",
        )
        otlp_port, metrics_port = port_for(name, 4318), port_for(name, 8888)
        wait_until(lambda: get_metrics(metrics_port), "Lab Collector did not start")
        fast = fixture_span("fast-success", 10)
        error = fixture_span("early-error", 10, error=True)
        slow = fixture_span("slow-success", 1000)
        late_id, late_root_id = uuid4().bytes, uuid4().bytes[:8]
        first = fixture_span("early-child-of-long-job", 10, trace_id=late_id, parent=late_root_id)
        send(otlp_port, [fast, error, slow, first])

        def decisions_ready():
            text = get_metrics(metrics_port)
            prefix = "otelcol_processor_tail_sampling_global_count_traces_sampled"
            return text if (
                metric_value(text, prefix, sampled="true") == 2
                and metric_value(text, prefix, sampled="false") == 2
            ) else None

        initial_metrics = wait_until(decisions_ready, "Tail did not decide the four initial traces")
        kept_path = mounted / "kept.json"
        wait_until(lambda: len(output_spans(kept_path)) == 2, "Tail output not flushed")
        # A late ERROR/root arrives only AFTER the Collector reports the drop.
        late = fixture_span(
            "late-error-root", 5000, error=True, trace_id=late_id, span_id=late_root_id,
        )
        send(otlp_port, [late])
        # A second known-keep trace is a positive flush barrier, avoiding a test
        # whose only evidence is an empty output after an arbitrary short sleep.
        barrier = fixture_span("after-late-error-barrier", 10, error=True)
        send(otlp_port, [barrier])
        wait_until(lambda: len(output_spans(kept_path)) >= 3, "Tail barrier was not exported")
        kept = output_spans(kept_path)
        names = {span["name"] for span in kept}
        require(names == {"early-error", "slow-success", "after-late-error-barrier"},
                "Tail policy retained an unexpected fixture or lost an expected one")
        final_metrics = get_metrics(metrics_port)
        require(metric_value(final_metrics, "otelcol_receiver_accepted_spans") == 6,
                "Collector did not accept all six synthetic spans")
        (output / "collector-metrics.txt").write_text(final_metrics)
        (output / "collector-initial-metrics.txt").write_text(initial_metrics)
        return {
            "image": IMAGE, "decision_wait_seconds": 1, "latency_threshold_ms": 500,
            "accepted_spans": 6, "exported_spans": len(kept),
            "exported_names": sorted(names),
            "fast_success_dropped": True, "early_error_retained": True,
            "slow_success_retained": True, "late_error_did_not_reverse_drop": True,
            "input_durations_are_fixtures_not_performance_measurements": True,
            "output": str(kept_path),
        }
    finally:
        try:
            log = subprocess.run(
                ["docker", "logs", name], check=False, capture_output=True,
                text=True, timeout=10,
            )
            (output / "collector.log").write_text(log.stdout + log.stderr)
        finally:
            docker("rm", "-f", name, check=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--head-only", action="store_true", help="Do not start a Docker Collector")
    args = parser.parse_args()
    output = Path(tempfile.mkdtemp(prefix="catchhole-gh271-stage6-sampling-"))
    result = {"head": verify_head(), "tail": None, "production_policy_changed": False}
    if not args.head_only:
        result["tail"] = verify_tail(output)
    target = output / "verification.json"
    target.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({
        "status": "passed", "result": str(target),
        "head_sampled_traces": [x["sampled_traces"] for x in result["head"]["trials"]],
        "tail": result["tail"],
    }, indent=2))


if __name__ == "__main__":
    main()
