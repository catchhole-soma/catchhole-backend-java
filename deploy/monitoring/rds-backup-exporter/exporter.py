"""Read one RDS instance on a timer; HTTP requests only read the cached result."""

from dataclasses import dataclass, replace
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import logging
import math
import os
import re
import signal
import threading
import time


LOG = logging.getLogger("rds_backup_exporter")
PREFIX = "catchhole_rds_backup_"
SNAPSHOT_PREFIX = "catchhole_rds_snapshot_"
RESOURCE_PREFIX = "catchhole_rds_resource_"
SNAPSHOT_POLL_SECONDS = 300
RESOURCE_POLL_SECONDS = 60
RESOURCE_SOURCE_MAX_AGE_SECONDS = 300


@dataclass(frozen=True)
class Settings:
    db_identifier: str
    region: str
    environment: str = "prod"
    listen_port: int = 9109
    poll_interval_seconds: float = 60
    enable_snapshot_collection: bool = False
    enable_resource_collection: bool = False

    @classmethod
    def from_environment(cls):
        db = os.environ.get("DB_IDENTIFIER", "")
        region = os.environ.get("AWS_REGION", "")
        environment = os.environ.get("ENVIRONMENT", "prod")
        try:
            port = int(os.environ.get("LISTEN_PORT", "9109"))
            interval = float(os.environ.get("POLL_INTERVAL_SECONDS", "60"))
        except ValueError:
            raise ValueError("invalid_numeric_configuration") from None
        if not re.fullmatch(r"[A-Za-z][A-Za-z0-9-]{0,62}", db) or db.endswith("-") or "--" in db:
            raise ValueError("invalid_db_identifier")
        if not re.fullmatch(r"[a-z0-9-]+", region):
            raise ValueError("invalid_region")
        if not environment or len(environment) > 64:
            raise ValueError("invalid_environment")
        if not 1024 <= port <= 65535 or not math.isfinite(interval) or interval < 30:
            raise ValueError("invalid_port_or_poll_interval")
        flags = []
        for name in ("ENABLE_SNAPSHOT_COLLECTION", "ENABLE_RESOURCE_COLLECTION"):
            value = os.environ.get(name, "false").lower()
            if value not in ("true", "false"):
                raise ValueError("invalid_collection_flag")
            flags.append(value == "true")
        return cls(db, region, environment, port, interval, *flags)


@dataclass(frozen=True)
class Snapshot:
    process_start_timestamp_seconds: float
    api_success: int = 0
    lrt_valid: int = 0
    latest_restorable_timestamp_seconds: float | None = None
    last_success_timestamp_seconds: float | None = None
    last_valid_observation_timestamp_seconds: float | None = None
    last_poll_timestamp_seconds: float | None = None
    poll_total: int = 0
    poll_errors_total: int = 0
    invalid_lrt_total: int = 0
    metadata_valid: int = 0
    retention_days: int | None = None
    allocated_storage_bytes: int | None = None
    backup_window_start_seconds: int | None = None
    backup_window_end_seconds: int | None = None
    last_metadata_valid_observation_timestamp_seconds: float | None = None


def make_client(settings, service):
    import boto3
    from botocore.config import Config

    # Default credential chain includes the EC2 role; never accept keys in config.
    return boto3.session.Session().client(
        service, region_name=settings.region,
        config=Config(connect_timeout=3, read_timeout=5,
                      retries={"mode": "standard", "total_max_attempts": 2}),
    )


def make_rds_client(settings):
    return make_client(settings, "rds")


def make_cloudwatch_client(settings):
    return make_client(settings, "cloudwatch")


def valid_timestamp(value, now):
    if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("invalid_timestamp")
    source = value.timestamp()
    if not math.isfinite(source) or not 0 < source <= now:
        raise ValueError("invalid_timestamp")
    return source


def instance_metadata(instance):
    retention, allocated = instance.get("BackupRetentionPeriod"), instance.get("AllocatedStorage")
    if type(retention) is not int or not 0 <= retention <= 35:
        raise ValueError("invalid_retention")
    if type(allocated) is not int or not 0 < allocated <= 10**9:
        raise ValueError("invalid_allocated_storage")
    window = instance.get("PreferredBackupWindow")
    if not isinstance(window, str) or not re.fullmatch(r"\d{2}:\d{2}-\d{2}:\d{2}", window):
        raise ValueError("invalid_backup_window")
    start_hour, start_minute, end_hour, end_minute = map(int, re.split("[:-]", window))
    if max(start_hour, end_hour) > 23 or max(start_minute, end_minute) > 59:
        raise ValueError("invalid_backup_window")
    start, end = start_hour * 3600 + start_minute * 60, end_hour * 3600 + end_minute * 60
    if (end - start) % 86400 < 1800:
        raise ValueError("invalid_backup_window")
    return {"retention_days": retention, "allocated_storage_bytes": allocated * 1024**3,
            "backup_window_start_seconds": start, "backup_window_end_seconds": end}


def run_poll_loop(poll, interval, stop, monotonic=time.monotonic):
    # Immediate first poll; slow calls never create catch-up bursts.
    deadline = monotonic()
    while not stop.is_set():
        poll()
        deadline += interval
        now = monotonic()
        if deadline <= now:
            deadline = now + interval
        if stop.wait(max(0, deadline - now)):
            return


class Collector:
    def __init__(self, settings, client_factory=make_rds_client, clock=time.time):
        self.settings = settings
        self._factory = client_factory
        self._client = None
        self._clock = clock
        self._lock = threading.Lock()
        self._snapshot = Snapshot(process_start_timestamp_seconds=clock())

    def snapshot(self):
        with self._lock:
            return self._snapshot

    def poll_once(self):
        previous = self.snapshot()
        try:
            if self._client is None:
                self._client = self._factory(self.settings)
            response = self._client.describe_db_instances(
                DBInstanceIdentifier=self.settings.db_identifier)
            instances = response.get("DBInstances", [])
            if len(instances) != 1 or instances[0].get("DBInstanceIdentifier") != self.settings.db_identifier:
                raise ValueError("unexpected_instance_response")
        except Exception:
            # SDK errors may contain endpoints, identifiers or credential details.
            # Log a fixed category only; never log the exception or AWS response.
            updated = replace(previous, api_success=0, lrt_valid=0, metadata_valid=0,
                              last_poll_timestamp_seconds=self._clock(),
                              poll_total=previous.poll_total + 1,
                              poll_errors_total=previous.poll_errors_total + 1)
            LOG.warning("event=rds_backup_poll result=api_error")
        else:
            now = self._clock()
            lrt = instances[0].get("LatestRestorableTime")
            source = None
            try:
                source = valid_timestamp(lrt, now)
            except (ValueError, OverflowError, OSError):
                pass
            valid = (source is not None and math.isfinite(source) and 0 < source <= now
                     and (previous.latest_restorable_timestamp_seconds is None
                          or source >= previous.latest_restorable_timestamp_seconds))
            updated = replace(previous, api_success=1, lrt_valid=int(valid),
                              last_success_timestamp_seconds=now,
                              last_poll_timestamp_seconds=now,
                              poll_total=previous.poll_total + 1,
                              invalid_lrt_total=previous.invalid_lrt_total + int(not valid))
            if valid:
                updated = replace(updated, latest_restorable_timestamp_seconds=source,
                                  last_valid_observation_timestamp_seconds=now)
            else:
                LOG.warning("event=rds_backup_poll result=invalid_lrt")
            # Metadata is independently useful when retention=0 makes LRT absent.
            # A malformed metadata field must never invalidate a valid LRT.
            try:
                metadata = instance_metadata(instances[0])
            except (ValueError, TypeError):
                updated = replace(updated, metadata_valid=0)
                LOG.warning("event=rds_backup_poll result=invalid_metadata")
            else:
                updated = replace(updated, metadata_valid=1,
                                  last_metadata_valid_observation_timestamp_seconds=now, **metadata)
        with self._lock:
            self._snapshot = updated

    def run(self, stop, monotonic=time.monotonic):
        run_poll_loop(self.poll_once, self.settings.poll_interval_seconds, stop, monotonic)


@dataclass(frozen=True)
class AutomatedSnapshotState:
    api_success: int = 0
    valid: int = 0
    has_available: int | None = None
    latest_available_timestamp_seconds: float | None = None
    latest_snapshot_failed: int | None = None
    # Internal high-water mark: omitting/deleting a failed item or beginning a
    # new snapshot is not proof that the last observed failure recovered.
    latest_terminal_timestamp_seconds: float | None = None
    last_valid_observation_timestamp_seconds: float | None = None
    last_poll_timestamp_seconds: float | None = None
    poll_total: int = 0
    poll_errors_total: int = 0


class SnapshotCollector:
    # Unknown statuses never become healthy data. An inventory with only creating
    # snapshots is successful, but it still has no available restore snapshot.
    KNOWN_STATUSES = {"available", "creating", "copying", "deleting", "failed"}
    MAX_PAGES = 100

    def __init__(self, settings, client_factory=make_rds_client, clock=time.time):
        self.settings, self._factory, self._clock = settings, client_factory, clock
        self._client = None
        self._lock = threading.Lock()
        self._snapshot = AutomatedSnapshotState()

    def snapshot(self):
        with self._lock:
            return self._snapshot

    def poll_once(self, stop=None):
        previous = self.snapshot()
        try:
            if self._client is None:
                self._client = self._factory(self.settings)
            marker, seen_markers = None, set()
            latest_available, latest_created, latest_failed = None, None, 0
            for _ in range(self.MAX_PAGES):
                if stop is not None and stop.is_set():
                    return
                params = {"DBInstanceIdentifier": self.settings.db_identifier,
                          "SnapshotType": "automated", "MaxRecords": 100}
                if marker is not None:
                    params["Marker"] = marker
                response = self._client.describe_db_snapshots(**params)
                records = response.get("DBSnapshots")
                if not isinstance(records, list) or len(records) > 100:
                    raise ValueError("invalid_snapshot_page")
                now = self._clock()
                for record in records:
                    if (not isinstance(record, dict)
                            or record.get("DBInstanceIdentifier") != self.settings.db_identifier
                            or record.get("SnapshotType") != "automated"
                            or record.get("Status") not in self.KNOWN_STATUSES):
                        raise ValueError("invalid_snapshot_identity_or_status")
                    created_at = record.get("SnapshotCreateTime")
                    if created_at is None and record["Status"] in {"creating", "copying", "deleting"}:
                        # RDS can omit creation time while work is in progress.
                        # This cannot hide an explicitly dated failed snapshot.
                        continue
                    created = valid_timestamp(created_at, now)
                    if record["Status"] not in {"available", "failed"}:
                        # Beginning another snapshot is not recovery evidence for
                        # the latest completed failed attempt, even when dated.
                        continue
                    if record["Status"] == "available":
                        latest_available = max(latest_available or 0, created)
                    if latest_created is None or created > latest_created:
                        latest_created, latest_failed = created, int(record["Status"] == "failed")
                    elif created == latest_created:
                        # If equal timestamps include a failure, never hide it by
                        # relying on API page ordering.
                        latest_failed = max(latest_failed, int(record["Status"] == "failed"))
                marker = response.get("Marker")
                if marker is None:
                    break
                if not isinstance(marker, str) or not 1 <= len(marker) <= 4096 or marker in seen_markers:
                    raise ValueError("invalid_snapshot_pagination")
                seen_markers.add(marker)
            else:
                raise ValueError("snapshot_page_limit_exceeded")
            if (latest_available is not None and previous.latest_available_timestamp_seconds is not None
                    and latest_available < previous.latest_available_timestamp_seconds):
                raise ValueError("snapshot_timestamp_regressed")
            prior_terminal = previous.latest_terminal_timestamp_seconds
            if prior_terminal is not None:
                if latest_created is None or latest_created < prior_terminal:
                    latest_created = prior_terminal
                    latest_failed = previous.latest_snapshot_failed or 0
                elif latest_created == prior_terminal:
                    # A previously observed failure needs a strictly newer
                    # available snapshot, even if its original item disappears.
                    latest_failed = max(latest_failed, previous.latest_snapshot_failed or 0)
        except Exception:
            updated = replace(previous, api_success=0, valid=0,
                last_poll_timestamp_seconds=self._clock(), poll_total=previous.poll_total + 1,
                poll_errors_total=previous.poll_errors_total + 1)
            LOG.warning("event=rds_snapshot_poll result=api_or_validation_error")
        else:
            now = self._clock()
            # Only the complete inventory replaces the cache. Successful empty
            # results clear available time, rather than reusing an old snapshot.
            updated = replace(previous, api_success=1, valid=1,
                has_available=int(latest_available is not None),
                latest_available_timestamp_seconds=latest_available,
                latest_snapshot_failed=latest_failed,
                latest_terminal_timestamp_seconds=latest_created,
                last_valid_observation_timestamp_seconds=now, last_poll_timestamp_seconds=now,
                poll_total=previous.poll_total + 1)
        with self._lock:
            self._snapshot = updated

    def run(self, stop, monotonic=time.monotonic):
        run_poll_loop(lambda: self.poll_once(stop), SNAPSHOT_POLL_SECONDS, stop, monotonic)


@dataclass(frozen=True)
class ResourceSample:
    value: float | None = None
    source_timestamp_seconds: float | None = None
    valid: int = 0
    api_success: int = 0
    last_success_timestamp_seconds: float | None = None
    last_poll_timestamp_seconds: float | None = None
    poll_total: int = 0
    poll_errors_total: int = 0
    invalid_total: int = 0


RESOURCE_METRICS = {
    "cpu_percent": ("CPUUtilization", "Average", "Percent"),
    "free_storage_bytes": ("FreeStorageSpace", "Minimum", "Bytes"),
    "database_connections": ("DatabaseConnections", "Maximum", "Count"),
}


class ResourceMetricCollector:
    def __init__(self, settings, metric, client_factory=make_cloudwatch_client, clock=time.time):
        self.settings, self.metric = settings, metric
        self._factory, self._clock, self._client = client_factory, clock, None
        self._lock = threading.Lock()
        self._snapshot = ResourceSample()

    def snapshot(self):
        with self._lock:
            return self._snapshot

    def poll_once(self):
        previous = self.snapshot()
        metric_name, statistic, unit = RESOURCE_METRICS[self.metric]
        try:
            if self._client is None:
                self._client = self._factory(self.settings)
            request_time = self._clock()
            # CloudWatch rounds StartTime down to a minute. Align both endpoints
            # ourselves and exclude the currently incomplete minute, so a valid
            # rounded first point is not rejected as outside the requested range.
            window_end = math.floor(request_time / 60) * 60
            window_start = window_end - 600
            response = self._client.get_metric_statistics(Namespace="AWS/RDS", MetricName=metric_name,
                Dimensions=[{"Name": "DBInstanceIdentifier", "Value": self.settings.db_identifier}],
                StartTime=datetime.fromtimestamp(window_start, timezone.utc),
                EndTime=datetime.fromtimestamp(window_end, timezone.utc),
                Period=60, Statistics=[statistic], Unit=unit)
        except Exception:
            updated = replace(previous, api_success=0, valid=0,
                last_poll_timestamp_seconds=self._clock(), poll_total=previous.poll_total + 1,
                poll_errors_total=previous.poll_errors_total + 1)
            LOG.warning("event=rds_resource_poll metric=%s result=api_error", self.metric)
        else:
            now = self._clock()
            try:
                points = response.get("Datapoints")
                if (response.get("Label") != metric_name or not isinstance(points, list)
                        or not 1 <= len(points) <= 20):
                    raise ValueError("invalid_resource_response")
                # CloudWatch does not sort datapoints. Validate the time/units of
                # every point, then select the latest; never fall back from a bad
                # newest value to an older healthy-looking value.
                latest = None
                for point in points:
                    source = valid_timestamp(point.get("Timestamp"), now)
                    if point.get("Unit") != unit or not window_start <= source < window_end:
                        raise ValueError("invalid_resource_unit")
                    if latest is None or source > latest[0]:
                        latest = source, point
                    elif source == latest[0] and point != latest[1]:
                        raise ValueError("ambiguous_resource_timestamp")
                source, point = latest
                value = point.get(statistic)
                if (type(value) not in (int, float) or not math.isfinite(value) or value < 0
                        or (self.metric == "cpu_percent" and value > 100)
                        or (self.metric == "database_connections" and int(value) != value)
                        or now - source > RESOURCE_SOURCE_MAX_AGE_SECONDS
                        or source < window_start
                        or (previous.source_timestamp_seconds is not None
                            and source < previous.source_timestamp_seconds)):
                    raise ValueError("invalid_resource_point")
            except (ValueError, TypeError, AttributeError, OverflowError):
                updated = replace(previous, api_success=1, valid=0,
                    last_poll_timestamp_seconds=now, poll_total=previous.poll_total + 1,
                    invalid_total=previous.invalid_total + 1)
                LOG.warning("event=rds_resource_poll metric=%s result=invalid_point", self.metric)
            else:
                updated = replace(previous, value=value, source_timestamp_seconds=source,
                    api_success=1, valid=1, last_success_timestamp_seconds=now,
                    last_poll_timestamp_seconds=now, poll_total=previous.poll_total + 1)
        with self._lock:
            self._snapshot = updated

    def run(self, stop, monotonic=time.monotonic):
        run_poll_loop(self.poll_once, RESOURCE_POLL_SECONDS, stop, monotonic)


class ResourceCollector:
    """One independent polling thread/client per metric, with immutable caches."""
    def __init__(self, settings, client_factory=make_cloudwatch_client, clock=time.time):
        self.settings = settings
        self.collectors = {metric: ResourceMetricCollector(settings, metric, client_factory, clock)
                           for metric in RESOURCE_METRICS}

    def snapshot(self):
        return {metric: collector.snapshot() for metric, collector in self.collectors.items()}

    def poll_once(self):
        # Deterministic helper for offline tests. Runtime uses separate threads.
        for collector in self.collectors.values():
            collector.poll_once()

    def run(self, stop):
        threads = [threading.Thread(target=collector.run, args=(stop,), daemon=True)
                   for collector in self.collectors.values()]
        for thread in threads:
            thread.start()
        stop.wait()
        deadline = time.monotonic() + 20
        for thread in threads:
            thread.join(timeout=max(0, deadline - time.monotonic()))


METRICS = {
    "latest_restorable_timestamp_seconds": "Last valid AWS latest restorable Unix timestamp; cached on failures.",
    "api_success": "Whether the last DescribeDBInstances call returned exactly the configured instance.",
    "last_success_timestamp_seconds": "Unix time of the last successful API response with matching instance.",
    "lrt_valid": "Whether the latest poll contains a timezone-aware, nonfuture, nonregressing LRT.",
    "last_valid_observation_timestamp_seconds": "Unix time when a valid LRT was last observed, even if unchanged.",
    "last_poll_timestamp_seconds": "Unix time when the polling loop last completed an attempt.",
    "poll_interval_seconds": "Configured interval between API polling attempts.",
    "process_start_timestamp_seconds": "Unix time when this collector started.",
    "poll_total": "Completed API polling attempts.",
    "poll_errors_total": "API polling attempts that failed or returned an unexpected instance.",
    "invalid_lrt_total": "Successful API responses with missing or invalid LRT.",
    "metadata_valid": "Whether the latest response has valid retention, allocation and UTC backup window metadata.",
    "retention_days": "Configured automated backup retention days; zero means automated backups disabled.",
    "allocated_storage_bytes": "RDS AllocatedStorage in bytes, converted from GiB.",
    "backup_window_start_seconds": "Preferred backup window start as UTC seconds since midnight.",
    "backup_window_end_seconds": "Preferred backup window end as UTC seconds since midnight; may cross midnight.",
    "last_metadata_valid_observation_timestamp_seconds": "Unix time when valid instance metadata was last observed.",
}

SNAPSHOT_METRICS = {
    "api_success": "Whether the complete automated snapshot inventory was returned and validated.",
    "valid": "Whether the latest full inventory is valid; previous inventory is cached on failures.",
    "has_available": "Whether the valid full inventory contains at least one available automated snapshot.",
    "latest_available_timestamp_seconds": "Creation Unix timestamp of the latest available automated snapshot.",
    "latest_snapshot_failed": "Whether the latest observed dated terminal snapshot failed; only a newer available snapshot clears a known failure; cache resets on process restart.",
    "last_valid_observation_timestamp_seconds": "Unix time when a complete valid snapshot inventory was observed.",
    "last_poll_timestamp_seconds": "Unix time when the snapshot polling attempt completed.",
    "poll_total": "Completed snapshot inventory polling attempts.",
    "poll_errors_total": "Snapshot inventory attempts with API, pagination or validation failures.",
}

RESOURCE_STATE_METRICS = {
    "source_timestamp_seconds": "Unix timestamp of the latest accepted CloudWatch datapoint.",
    "valid": "Whether the last poll yielded a valid nonregressing datapoint no older than 300 seconds.",
    "api_success": "Whether the last GetMetricStatistics API call succeeded.",
    "last_success_timestamp_seconds": "Unix time when a valid CloudWatch datapoint was last observed.",
    "last_poll_timestamp_seconds": "Unix time when the resource polling attempt completed.",
    "poll_total": "Completed resource polling attempts.",
    "poll_errors_total": "Failed GetMetricStatistics API calls.",
    "invalid_total": "Successful calls with empty, stale, malformed or invalid datapoints.",
}


def escape_label(value):
    return value.replace("\\", "\\\\").replace("\n", "\\n").replace('"', '\\"')


def metric_lines(prefix, label_text, values):
    lines = []
    for field, help_text, value in values:
        if value is None:
            continue  # Missing source data must never become a fabricated zero.
        name = prefix + field
        metric_type = "counter" if field.endswith("_total") else "gauge"
        lines.extend([f"# HELP {name} {help_text}", f"# TYPE {name} {metric_type}",
                      f"{name}{{{label_text}}} {value}"])
    return lines


def render_metrics(collector, additional_collectors=()):
    snapshot = collector.snapshot()
    settings = collector.settings
    labels = {"environment": settings.environment, "region": settings.region,
              "db_instance_identifier": settings.db_identifier}
    label_text = ",".join(f'{key}="{escape_label(value)}"' for key, value in labels.items())
    lines = metric_lines(PREFIX, label_text, (
        (field, help_text, settings.poll_interval_seconds if field == "poll_interval_seconds" else getattr(snapshot, field))
        for field, help_text in METRICS.items()))
    for additional in additional_collectors:
        if isinstance(additional, SnapshotCollector):
            cached = additional.snapshot()
            lines.extend(metric_lines(SNAPSHOT_PREFIX, label_text,
                         ((field, help_text, getattr(cached, field)) for field, help_text in SNAPSHOT_METRICS.items())))
        elif isinstance(additional, ResourceCollector):
            for metric, cached in additional.snapshot().items():
                values = [(metric, f"Latest valid AWS/RDS {RESOURCE_METRICS[metric][0]} {RESOURCE_METRICS[metric][1]} value.", cached.value)]
                values.extend((metric + "_" + field, help_text, getattr(cached, field))
                              for field, help_text in RESOURCE_STATE_METRICS.items())
                lines.extend(metric_lines(RESOURCE_PREFIX, label_text, values))
    return ("\n".join(lines) + "\n").encode("utf-8")


def make_server(collector, address=None, additional_collectors=()):
    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            super().setup()
            self.connection.settimeout(5)

        def do_GET(self):
            if self.path == "/metrics":
                body = render_metrics(collector, additional_collectors)
                status, content_type = 200, "text/plain; version=0.0.4; charset=utf-8"
            elif self.path == "/healthz":
                # Process liveness only: AWS success/freshness lives in metrics.
                body = b"ok\n"
                status, content_type = 200, "text/plain; charset=utf-8"
            else:
                body = b"not found\n"
                status, content_type = 404, "text/plain; charset=utf-8"
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *args):
            pass  # Arbitrary request paths must not enter logs.

    return ThreadingHTTPServer(address or ("0.0.0.0", collector.settings.listen_port), Handler)


def optional_collectors(settings):
    collectors = []
    if settings.enable_snapshot_collection:
        collectors.append(SnapshotCollector(settings))
    if settings.enable_resource_collection:
        collectors.append(ResourceCollector(settings))
    return collectors


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    # SDK credential lookup remains bounded for the intended EC2 instance role.
    os.environ.setdefault("AWS_METADATA_SERVICE_TIMEOUT", "1")
    os.environ.setdefault("AWS_METADATA_SERVICE_NUM_ATTEMPTS", "1")
    try:
        settings = Settings.from_environment()
    except ValueError as error:
        LOG.error("configuration_error=%s", error)
        return 1
    collector = Collector(settings)
    additional = optional_collectors(settings)
    stop = threading.Event()
    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, lambda *_: stop.set())
    server = make_server(collector, additional_collectors=additional)
    server.timeout = 0.5
    pollers = [threading.Thread(target=source.run, args=(stop,), daemon=True)
               for source in [collector, *additional]]
    for poller in pollers:
        poller.start()
    try:
        while not stop.is_set():
            server.handle_request()
    finally:
        stop.set()
        server.server_close()
        deadline = time.monotonic() + 20
        for poller in pollers:
            poller.join(timeout=max(0, deadline - time.monotonic()))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
