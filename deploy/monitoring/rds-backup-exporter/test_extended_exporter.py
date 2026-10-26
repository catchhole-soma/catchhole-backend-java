from datetime import datetime, timezone
import math
import os
import threading
import unittest
from unittest.mock import patch
from urllib.request import urlopen

import boto3
from botocore.stub import Stubber

from exporter import (Collector, ResourceCollector, ResourceMetricCollector, Settings, SnapshotCollector,
                      instance_metadata, make_rds_client, make_server, optional_collectors, render_metrics)
from test_exporter import FakeClient, response


SETTINGS = Settings("test-db", "ap-northeast-2")


def date(seconds):
    return datetime.fromtimestamp(seconds, timezone.utc)


def sdk_client(service):
    # Explicit dummy credentials and a loopback endpoint prevent accidental AWS
    # or instance-role access. Stubber intercepts every expected API request.
    return boto3.client(service, region_name="ap-northeast-2", aws_access_key_id="test",
                        aws_secret_access_key="test", endpoint_url="http://127.0.0.1:9")


def metadata_response(lrt=700, **overrides):
    result = response(lrt)
    result["DBInstances"][0].update(BackupRetentionPeriod=7, AllocatedStorage=20,
                                    PreferredBackupWindow="23:45-00:15")
    result["DBInstances"][0].update(overrides)
    return result


def snapshot_item(created=700, status="available", **overrides):
    return {"DBInstanceIdentifier": "test-db", "SnapshotType": "automated", "Status": status,
            "SnapshotCreateTime": date(created), **overrides}


def snapshot_params(marker=None):
    result = {"DBInstanceIdentifier": "test-db", "SnapshotType": "automated", "MaxRecords": 100}
    if marker:
        result["Marker"] = marker
    return result


def resource_params(metric="CPUUtilization", statistic="Average", unit="Percent", now=1000):
    end = math.floor(now / 60) * 60
    return {"Namespace": "AWS/RDS", "MetricName": metric,
            "Dimensions": [{"Name": "DBInstanceIdentifier", "Value": "test-db"}],
            "StartTime": date(end - 600), "EndTime": date(end), "Period": 60,
            "Statistics": [statistic], "Unit": unit}


def cpu_response(timestamp=940, value=70):
    return {"Label": "CPUUtilization", "Datapoints": [
        {"Timestamp": date(timestamp), "Average": value, "Unit": "Percent"}]}


class MetadataTest(unittest.TestCase):
    def test_retention_zero_remains_valid_when_lrt_disappears(self):
        client = FakeClient(metadata_response(None, BackupRetentionPeriod=0))
        collector = Collector(SETTINGS, lambda _: client, lambda: 1000)
        collector.poll_once()
        state = collector.snapshot()
        self.assertEqual((state.api_success, state.lrt_valid, state.metadata_valid), (1, 0, 1))
        self.assertEqual(state.retention_days, 0)
        self.assertEqual(state.allocated_storage_bytes, 20 * 1024**3)
        self.assertEqual((state.backup_window_start_seconds, state.backup_window_end_seconds), (85500, 900))
        self.assertEqual(state.last_metadata_valid_observation_timestamp_seconds, 1000)

    def test_invalid_metadata_and_api_failure_preserve_cache_without_blocking_lrt(self):
        now = [1000]
        client = FakeClient(metadata_response(), metadata_response(800, PreferredBackupWindow="bad"),
                            RuntimeError("private-credential-do-not-log"))
        collector = Collector(SETTINGS, lambda _: client, lambda: now[0])
        collector.poll_once()
        now[0] = 1060
        collector.poll_once()
        state = collector.snapshot()
        self.assertEqual((state.lrt_valid, state.metadata_valid), (1, 0))
        self.assertEqual(state.latest_restorable_timestamp_seconds, 800)
        self.assertEqual(state.retention_days, 7)
        self.assertEqual(state.last_metadata_valid_observation_timestamp_seconds, 1000)
        now[0] = 1120
        with self.assertLogs("rds_backup_exporter") as logs:
            collector.poll_once()
        self.assertNotIn("private-credential", str(logs.output))
        self.assertEqual(collector.snapshot().retention_days, 7)
        self.assertEqual(collector.snapshot().metadata_valid, 0)

    def test_metadata_rejects_impossible_values_and_zero_length_window(self):
        for changes in ({"BackupRetentionPeriod": -1}, {"BackupRetentionPeriod": True},
                        {"BackupRetentionPeriod": 36}, {"AllocatedStorage": 0},
                        {"AllocatedStorage": float("nan")}, {"PreferredBackupWindow": "24:00-00:30"},
                        {"PreferredBackupWindow": "00:00-00:00"}, {"PreferredBackupWindow": "00:00-00:15"}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                instance_metadata(metadata_response(**changes)["DBInstances"][0])


class SnapshotTest(unittest.TestCase):
    def test_all_pages_are_consumed_before_publishing_latest_available_and_latest_failure(self):
        client = sdk_client("rds")
        with Stubber(client) as stub:
            stub.add_response("describe_db_snapshots", {"DBSnapshots": [snapshot_item(800)], "Marker": "page-2"}, snapshot_params())
            stub.add_response("describe_db_snapshots", {"DBSnapshots": [snapshot_item(900, "failed"), snapshot_item(700)]}, snapshot_params("page-2"))
            collector = SnapshotCollector(SETTINGS, lambda _: client, lambda: 1000)
            collector.poll_once()
            state = collector.snapshot()
            self.assertEqual((state.api_success, state.valid, state.has_available, state.latest_snapshot_failed), (1, 1, 1, 1))
            self.assertEqual(state.latest_available_timestamp_seconds, 800)
            self.assertEqual(state.last_valid_observation_timestamp_seconds, 1000)
            stub.assert_no_pending_responses()

    def test_later_page_failure_cannot_publish_partial_or_empty_inventory(self):
        client = sdk_client("rds")
        now = [1000]
        with Stubber(client) as stub:
            stub.add_response("describe_db_snapshots", {"DBSnapshots": [snapshot_item(700)]}, snapshot_params())
            stub.add_response("describe_db_snapshots", {"DBSnapshots": [snapshot_item(900)], "Marker": "next"}, snapshot_params())
            stub.add_client_error("describe_db_snapshots", "AccessDenied", "do-not-log-sensitive-detail", expected_params=snapshot_params("next"))
            collector = SnapshotCollector(SETTINGS, lambda _: client, lambda: now[0])
            collector.poll_once()
            now[0] = 1060
            with self.assertLogs("rds_backup_exporter") as logs:
                collector.poll_once()
            self.assertNotIn("do-not-log", str(logs.output))
            state = collector.snapshot()
            self.assertEqual((state.api_success, state.valid, state.poll_errors_total), (0, 0, 1))
            self.assertEqual(state.latest_available_timestamp_seconds, 700)
            self.assertEqual(state.last_valid_observation_timestamp_seconds, 1000)
            stub.assert_no_pending_responses()

    def test_empty_success_is_distinct_from_api_failure_and_clears_old_available_time(self):
        client = sdk_client("rds")
        with Stubber(client) as stub:
            stub.add_response("describe_db_snapshots", {"DBSnapshots": [snapshot_item()]}, snapshot_params())
            stub.add_response("describe_db_snapshots", {"DBSnapshots": []}, snapshot_params())
            collector = SnapshotCollector(SETTINGS, lambda _: client, lambda: 1000)
            collector.poll_once()
            collector.poll_once()
            state = collector.snapshot()
            self.assertEqual((state.api_success, state.valid, state.has_available, state.latest_snapshot_failed), (1, 1, 0, 0))
            self.assertIsNone(state.latest_available_timestamp_seconds)

    def test_bad_identity_time_status_and_marker_loop_invalidate_without_erasing_cache(self):
        bad_pages = [
            {"DBSnapshots": [snapshot_item(DBInstanceIdentifier="other-db")]},
            {"DBSnapshots": [snapshot_item(SnapshotType="manual")]},
            {"DBSnapshots": [snapshot_item(status="new-unknown-status")]},
            {"DBSnapshots": [snapshot_item(2000)]},
            {"DBSnapshots": [snapshot_item(SnapshotCreateTime=datetime(2020, 1, 1))]},
            {"DBSnapshots": [snapshot_item(699)]},
            {"DBSnapshots": [] , "Marker": "again"},
        ]
        for bad in bad_pages:
            with self.subTest(page=bad):
                class Client:
                    calls = 0
                    def describe_db_snapshots(self, **_):
                        self.calls += 1
                        return {"DBSnapshots": [snapshot_item()]} if self.calls == 1 else bad
                collector = SnapshotCollector(SETTINGS, lambda _: Client(), lambda: 1000)
                collector.poll_once()
                collector.poll_once()
                self.assertEqual(collector.snapshot().valid, 0)
                self.assertEqual(collector.snapshot().latest_available_timestamp_seconds, 700)
                self.assertLessEqual(collector._client.calls, 3)

    def test_creating_is_not_failure_or_available_and_stop_interrupts_pagination(self):
        client = sdk_client("rds")
        with Stubber(client) as stub:
            stub.add_response("describe_db_snapshots", {"DBSnapshots": [snapshot_item(status="creating")]}, snapshot_params())
            collector = SnapshotCollector(SETTINGS, lambda _: client, lambda: 1000)
            collector.poll_once()
            self.assertEqual((collector.snapshot().valid, collector.snapshot().has_available, collector.snapshot().latest_snapshot_failed), (1, 0, 0))
        stop = threading.Event()
        class Client:
            def describe_db_snapshots(self, **_):
                stop.set()
                return {"DBSnapshots": [snapshot_item()], "Marker": "second"}
        collector = SnapshotCollector(SETTINGS, lambda _: Client(), lambda: 1000)
        collector.poll_once(stop)
        self.assertEqual(collector.snapshot().poll_total, 0)
        self.assertIsNone(collector.snapshot().latest_available_timestamp_seconds)

    def test_undated_progress_is_valid_but_cannot_hide_failure_until_dated_available(self):
        client = sdk_client("rds")
        undated = snapshot_item(status="creating")
        del undated["SnapshotCreateTime"]
        with Stubber(client) as stub:
            stub.add_response("describe_db_snapshots", {"DBSnapshots": [snapshot_item(800, "failed"), undated]}, snapshot_params())
            stub.add_response("describe_db_snapshots", {"DBSnapshots": [snapshot_item(800, "failed"), snapshot_item(850, "creating")]}, snapshot_params())
            stub.add_response("describe_db_snapshots", {"DBSnapshots": [undated]}, snapshot_params())
            stub.add_response("describe_db_snapshots", {"DBSnapshots": [snapshot_item(900)]}, snapshot_params())
            collector = SnapshotCollector(SETTINGS, lambda _: client, lambda: 1000)
            collector.poll_once()
            self.assertEqual((collector.snapshot().valid, collector.snapshot().latest_snapshot_failed), (1, 1))
            collector.poll_once()
            self.assertEqual((collector.snapshot().valid, collector.snapshot().latest_snapshot_failed), (1, 1))
            collector.poll_once()
            self.assertEqual((collector.snapshot().valid, collector.snapshot().latest_snapshot_failed), (1, 1))
            collector.poll_once()
            self.assertEqual((collector.snapshot().valid, collector.snapshot().latest_snapshot_failed,
                              collector.snapshot().latest_available_timestamp_seconds), (1, 0, 900))
        for status in ("available", "failed"):
            class Client:
                def describe_db_snapshots(self, **_):
                    return {"DBSnapshots": [{**undated, "Status": status}]}
            collector = SnapshotCollector(SETTINGS, lambda _: Client(), lambda: 1000)
            collector.poll_once()
            self.assertEqual(collector.snapshot().valid, 0)

    def test_disappearing_failed_item_old_available_empty_and_equal_time_do_not_claim_recovery(self):
        client = sdk_client("rds")
        inventories = [
            [snapshot_item(800), snapshot_item(900, "failed")],
            [snapshot_item(800), snapshot_item(1000, "creating")],
            [snapshot_item(800)],
            [],
            [snapshot_item(900)],
            [snapshot_item(1000)],
        ]
        with Stubber(client) as stub:
            for inventory in inventories:
                stub.add_response("describe_db_snapshots", {"DBSnapshots": inventory}, snapshot_params())
            collector = SnapshotCollector(SETTINGS, lambda _: client, lambda: 1100)
            for index in range(len(inventories)):
                collector.poll_once()
                state = collector.snapshot()
                self.assertEqual(state.valid, 1)
                self.assertEqual(state.latest_snapshot_failed, int(index < 5))
                self.assertEqual(state.latest_terminal_timestamp_seconds, 900 if index < 5 else 1000)
                if index == 3:
                    self.assertEqual(state.has_available, 0)
                    self.assertIsNone(state.latest_available_timestamp_seconds)
            stub.assert_no_pending_responses()


class ResourceTest(unittest.TestCase):
    def test_query_window_is_minute_aligned_and_accepts_rounded_first_point(self):
        client = sdk_client("cloudwatch")
        now = 1001.75
        with Stubber(client) as stub:
            stub.add_response("get_metric_statistics", {"Label": "CPUUtilization", "Datapoints": [
                *cpu_response(360, 10)["Datapoints"], *cpu_response(900, 70)["Datapoints"]]},
                resource_params(now=now))
            collector = ResourceMetricCollector(SETTINGS, "cpu_percent", lambda _: client, lambda: now)
            collector.poll_once()
            self.assertEqual(collector.snapshot().valid, 1)
            self.assertEqual(collector.snapshot().source_timestamp_seconds, 900)
            stub.assert_no_pending_responses()

    def test_unsorted_datapoints_select_latest_and_same_source_refreshes_observation(self):
        client = sdk_client("cloudwatch")
        now = [1000]
        points = [cpu_response(900, 20)["Datapoints"][0], cpu_response(940, 95)["Datapoints"][0], cpu_response(920, 30)["Datapoints"][0]]
        with Stubber(client) as stub:
            stub.add_response("get_metric_statistics", {"Label": "CPUUtilization", "Datapoints": points}, resource_params())
            stub.add_response("get_metric_statistics", cpu_response(940, 95), resource_params(now=1060))
            collector = ResourceMetricCollector(SETTINGS, "cpu_percent", lambda _: client, lambda: now[0])
            collector.poll_once()
            self.assertEqual((collector.snapshot().value, collector.snapshot().source_timestamp_seconds), (95, 940))
            now[0] = 1060
            collector.poll_once()
            self.assertEqual(collector.snapshot().last_success_timestamp_seconds, 1060)
            self.assertEqual(collector.snapshot().valid, 1)
            stub.assert_no_pending_responses()

    def test_resource_request_uses_each_required_aggregation_and_units(self):
        specs = [("cpu_percent", "CPUUtilization", "Average", "Percent", 60),
                 ("free_storage_bytes", "FreeStorageSpace", "Minimum", "Bytes", 1024),
                 ("database_connections", "DatabaseConnections", "Maximum", "Count", 27)]
        for field, metric, statistic, unit, value in specs:
            with self.subTest(metric=metric):
                client = sdk_client("cloudwatch")
                with Stubber(client) as stub:
                    stub.add_response("get_metric_statistics", {"Label": metric, "Datapoints": [
                        {"Timestamp": date(940), statistic: value, "Unit": unit}]}, resource_params(metric, statistic, unit))
                    collector = ResourceMetricCollector(SETTINGS, field, lambda _: client, lambda: 1000)
                    collector.poll_once()
                    self.assertEqual(collector.snapshot().value, value)
                    self.assertEqual(collector.snapshot().valid, 1)
                    stub.assert_no_pending_responses()

    def test_api_failure_preserves_value_and_empty_data_never_becomes_zero(self):
        client = sdk_client("cloudwatch")
        with Stubber(client) as stub:
            stub.add_response("get_metric_statistics", cpu_response(), resource_params())
            stub.add_client_error("get_metric_statistics", "AccessDenied", "sensitive-error", expected_params=resource_params())
            stub.add_response("get_metric_statistics", {"Label": "CPUUtilization", "Datapoints": []}, resource_params())
            collector = ResourceMetricCollector(SETTINGS, "cpu_percent", lambda _: client, lambda: 1000)
            collector.poll_once()
            with self.assertLogs("rds_backup_exporter") as logs:
                collector.poll_once()
            self.assertNotIn("sensitive-error", str(logs.output))
            self.assertEqual((collector.snapshot().api_success, collector.snapshot().valid), (0, 0))
            self.assertEqual(collector.snapshot().value, 70)
            collector.poll_once()
            self.assertEqual((collector.snapshot().api_success, collector.snapshot().valid), (1, 0))
            self.assertEqual(collector.snapshot().value, 70)
            self.assertEqual((collector.snapshot().poll_errors_total, collector.snapshot().invalid_total), (1, 1))

    def test_future_stale_regression_nan_bad_units_and_newest_invalid_do_not_hide_behind_old_values(self):
        bad_responses = [cpu_response(1001), cpu_response(699), cpu_response(939),
            cpu_response(value=math.nan), cpu_response(value=math.inf), cpu_response(value=-1),
            cpu_response(value=101), cpu_response(value=True), cpu_response(value="70"),
            {"Label": "CPUUtilization", "Datapoints": [{"Timestamp": date(940), "Average": 70, "Unit": "Count"}]},
            {"Label": "OtherMetric", "Datapoints": cpu_response()["Datapoints"]},
            {"Label": "CPUUtilization", "Datapoints": [*cpu_response()["Datapoints"], *cpu_response(950, math.nan)["Datapoints"]]},
        ]
        for bad in bad_responses:
            with self.subTest(response=bad):
                class Client:
                    calls = 0
                    def get_metric_statistics(self, **_):
                        self.calls += 1
                        return cpu_response() if self.calls == 1 else bad
                collector = ResourceMetricCollector(SETTINGS, "cpu_percent", lambda _: Client(), lambda: 1000)
                collector.poll_once()
                collector.poll_once()
                self.assertEqual((collector.snapshot().api_success, collector.snapshot().valid), (1, 0))
                self.assertEqual(collector.snapshot().value, 70)
                self.assertEqual(collector.snapshot().source_timestamp_seconds, 940)

    def test_slow_cpu_call_does_not_block_other_metrics_or_http_and_threads_stop(self):
        entered, release, storage, connections, stop = [threading.Event() for _ in range(5)]
        class Client:
            def get_metric_statistics(self, **kwargs):
                metric = kwargs["MetricName"]
                if metric == "CPUUtilization":
                    entered.set()
                    release.wait(2)
                elif metric == "FreeStorageSpace":
                    storage.set()
                else:
                    connections.set()
                return {"Label": metric, "Datapoints": [{"Timestamp": date(940),
                    kwargs["Statistics"][0]: 20, "Unit": kwargs["Unit"]}]}
        resource = ResourceCollector(SETTINGS, lambda _: Client(), lambda: 1000)
        runner = threading.Thread(target=resource.run, args=(stop,))
        runner.start()
        try:
            self.assertTrue(entered.wait(1))
            self.assertTrue(storage.wait(1))
            self.assertTrue(connections.wait(1))
            text = render_metrics(Collector(SETTINGS, clock=lambda: 1000), [resource])
            self.assertIn(b"catchhole_rds_resource_cpu_percent_valid", text)
            self.assertNotIn(b"catchhole_rds_resource_cpu_percent{", text)
        finally:
            stop.set()
            release.set()
            runner.join(2)
        self.assertFalse(runner.is_alive())


class ExtendedIntegrationTest(unittest.TestCase):
    def test_collections_are_opt_in_and_no_clients_are_created_by_scraping(self):
        with patch.dict(os.environ, {"DB_IDENTIFIER": "test-db", "AWS_REGION": "ap-northeast-2"}, clear=True):
            self.assertEqual(optional_collectors(Settings.from_environment()), [])
            os.environ.update(ENABLE_SNAPSHOT_COLLECTION="true", ENABLE_RESOURCE_COLLECTION="true")
            settings = Settings.from_environment()
            additional = optional_collectors(settings)
        self.assertIsInstance(additional[0], SnapshotCollector)
        self.assertIsInstance(additional[1], ResourceCollector)
        collector = Collector(settings)
        server = make_server(collector, ("127.0.0.1", 0), additional)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with patch("boto3.session.Session", side_effect=AssertionError("scrape must not create SDK client")):
                with urlopen(f"http://127.0.0.1:{server.server_port}/metrics", timeout=2) as response:
                    body = response.read()
            self.assertIn(b"catchhole_rds_snapshot_valid", body)
            self.assertIn(b"catchhole_rds_resource_free_storage_bytes_valid", body)
            self.assertNotIn(b"catchhole_rds_snapshot_latest_available_timestamp_seconds", body)
            self.assertNotIn(b"catchhole_rds_resource_free_storage_bytes{", body)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(2)

    def test_flags_reject_ambiguous_enable_values_and_sdk_timeouts_are_bounded(self):
        with patch.dict(os.environ, {"DB_IDENTIFIER": "test-db", "AWS_REGION": "ap-northeast-2", "ENABLE_RESOURCE_COLLECTION": "1"}, clear=True):
            with self.assertRaises(ValueError):
                Settings.from_environment()
        with patch("boto3.session.Session") as session:
            make_rds_client(SETTINGS)
            config = session.return_value.client.call_args.kwargs["config"]
            self.assertEqual((config.connect_timeout, config.read_timeout), (3, 5))
            self.assertEqual(config.retries["total_max_attempts"], 2)


if __name__ == "__main__":
    unittest.main()
