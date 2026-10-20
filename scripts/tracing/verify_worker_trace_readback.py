"""Read every stage-6 synthetic Worker span back through local Grafana/Tempo."""

from __future__ import annotations

import argparse
import base64
import json
import re
import time
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import ProxyHandler, Request, build_opener

from verify_worker_spring import REPO, grafana_auth, require


def read_trace(trace_id, expected_ids, auth, opener):
    url = ("http://127.0.0.1:3001/api/datasources/proxy/uid/catchhole-tempo"
           f"/api/traces/{trace_id}")
    for _ in range(40):
        try:
            request = Request(url, headers={"Authorization": auth, "Accept": "application/json"})
            with opener.open(request, timeout=3) as response:
                body = json.load(response)
            spans = [
                span for batch in body.get("batches", [])
                for scope in batch.get("scopeSpans", []) for span in scope.get("spans", [])
            ]
            ids = {base64.b64decode(span["spanId"]).hex() for span in spans}
            if ids == expected_ids:
                return spans
        except HTTPError as exc:
            if exc.code != 404:
                raise RuntimeError(f"Local Grafana returned HTTP {exc.code}") from None
        time.sleep(0.25)
    raise RuntimeError("Tempo did not return the exact synthetic Span ID set")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path, help="AI verify_worker_scenarios.py output")
    args = parser.parse_args()
    source = json.loads(args.report.read_text())
    require(source.get("schema_version") == 1 and source.get("synthetic") is True
            and source.get("passed") is True and source.get("otlp", {}).get("enabled") is True,
            "Expected a successful stage-6 synthetic OTLP report")
    require(source.get("service_name") == "catchhole-ai-worker-stage6-verification",
            "Unexpected report service")
    require(bool(source.get("scenarios")) and source.get("trace_count", 0) > 0
            and source.get("span_count", 0) > 0
            and all(item.get("traces") for item in source["scenarios"]),
            "Expected nonempty scenarios and positive Trace/Span totals")
    target = args.report.with_name(args.report.stem + "-tempo-readback.json")
    require(not target.exists(), "Readback output already exists")
    auth = grafana_auth(REPO / "monitoring/.env.tracing.local")
    opener = build_opener(ProxyHandler({}))
    verified = []
    for scenario in source["scenarios"]:
        for expected in scenario["traces"]:
            trace_id = expected["trace_id"]
            require(re.fullmatch(r"[0-9a-f]{32}", trace_id) is not None, "Invalid synthetic Trace ID")
            expected_by_id = {span["span_id"]: span for span in expected["spans"]}
            require(bool(expected_by_id)
                    and len(expected_by_id) == expected["expected_span_count"],
                    "Missing or duplicate expected spans")
            actual = read_trace(trace_id, set(expected_by_id), auth, opener)
            for span in actual:
                reference = expected_by_id[base64.b64decode(span["spanId"]).hex()]
                parent = base64.b64decode(span.get("parentSpanId", "")).hex() or None
                require(parent == reference["parent_span_id"], "Stored parent relationship changed")
                require(span["name"] == reference["name"], "Stored span name changed")
                require(int(span["startTimeUnixNano"]) == reference["start_unix_nano"]
                        and int(span["endTimeUnixNano"]) == reference["end_unix_nano"],
                        "Stored span timestamps changed")
            verified.append({
                "scenario": scenario["scenario"], "trace_id": trace_id,
                "stored_span_count": len(actual), "ids_names_parents_times_match": True,
            })
    require(len(verified) == source["trace_count"]
            and sum(item["stored_span_count"] for item in verified) == source["span_count"],
            "Readback totals differ from generation totals")
    result = {
        "synthetic": True, "passed": True, "source_report": str(args.report.resolve()),
        "trace_count": len(verified), "span_count": source["span_count"], "traces": verified,
        "verification": "Local Grafana datasource proxy API; not browser or production verification",
    }
    with target.open("x") as output:
        json.dump(result, output, indent=2)
        output.write("\n")
    print(json.dumps({"result": str(target), **result}, indent=2))


if __name__ == "__main__":
    main()
