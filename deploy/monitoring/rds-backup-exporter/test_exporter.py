from datetime import datetime, timezone
import os
import threading
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import urlopen

from exporter import Collector, Settings, escape_label, make_server, render_metrics


def response(timestamp=700, identifier="test-db"):
    instance = {"DBInstanceIdentifier": identifier}
    if timestamp is not None:
        instance["LatestRestorableTime"] = datetime.fromtimestamp(timestamp, timezone.utc)
    return {"DBInstances": [instance]}


class FakeClient:
    def __init__(self, *outcomes):
        self.outcomes = list(outcomes)
        self.calls = []

    def describe_db_instances(self, **kwargs):
        self.calls.append(kwargs)
        outcome = self.outcomes.pop(0)
        if isinstance(outcome, Exception):
            raise outcome
        return outcome


class CollectorTest(unittest.TestCase):
    def collector(self, *outcomes):
        self.now = 1000
        self.client = FakeClient(*outcomes)
        return Collector(Settings("test-db", "ap-northeast-2"),
                         lambda _: self.client, lambda: self.now)

    def test_initial_state_does_not_claim_a_timestamp_or_success(self):
        collector = self.collector()
        self.assertEqual(collector.snapshot().api_success, 0)
        self.assertEqual(collector.snapshot().lrt_valid, 0)
        text = render_metrics(collector).decode()
        self.assertNotIn("catchhole_rds_backup_latest_restorable_timestamp_seconds", text)
        self.assertNotIn("catchhole_rds_backup_last_success_timestamp_seconds", text)
        self.assertNotIn("catchhole_rds_backup_last_poll_timestamp_seconds", text)
        self.assertEqual(self.client.calls, [])

    def test_valid_failure_and_recovery_preserve_then_refresh_cache(self):
        collector = self.collector(response(), RuntimeError("secret-do-not-log"), response(820))
        collector.poll_once()
        self.assertEqual(self.client.calls, [{"DBInstanceIdentifier": "test-db"}])
        first = collector.snapshot()
        self.assertEqual((first.api_success, first.lrt_valid), (1, 1))
        self.assertEqual(first.last_success_timestamp_seconds, 1000)
        self.now = 1060
        with self.assertLogs("rds_backup_exporter", level="WARNING") as logs:
            collector.poll_once()
        failed = collector.snapshot()
        self.assertNotIn("secret-do-not-log", str(logs.output))
        self.assertEqual((failed.api_success, failed.lrt_valid), (0, 0))
        self.assertEqual(failed.latest_restorable_timestamp_seconds, 700)
        self.assertEqual(failed.last_success_timestamp_seconds, 1000)
        self.assertEqual(failed.last_valid_observation_timestamp_seconds, 1000)
        self.assertEqual(failed.last_poll_timestamp_seconds, 1060)
        self.now = 1120
        collector.poll_once()
        restored = collector.snapshot()
        self.assertEqual((restored.api_success, restored.lrt_valid), (1, 1))
        self.assertEqual(restored.latest_restorable_timestamp_seconds, 820)
        self.assertEqual(restored.last_valid_observation_timestamp_seconds, 1120)
        self.assertEqual((restored.poll_total, restored.poll_errors_total), (3, 1))

    def test_unchanged_source_is_a_fresh_successful_observation(self):
        collector = self.collector(response(), response())
        collector.poll_once()
        self.now += 60
        collector.poll_once()
        self.assertEqual(collector.snapshot().latest_restorable_timestamp_seconds, 700)
        self.assertEqual(collector.snapshot().last_valid_observation_timestamp_seconds, 1060)
        self.assertEqual(collector.snapshot().lrt_valid, 1)

    def test_missing_future_naive_wrong_type_and_regression_are_invalid(self):
        invalid = [response(None), response(2000), response(699),
                   {"DBInstances": [{"DBInstanceIdentifier": "test-db", "LatestRestorableTime": "700"}]},
                   {"DBInstances": [{"DBInstanceIdentifier": "test-db", "LatestRestorableTime": datetime(2020, 1, 1)}]}]
        for bad in invalid:
            with self.subTest(response=bad):
                collector = self.collector(response(), bad)
                collector.poll_once()
                self.now += 60
                with self.assertLogs("rds_backup_exporter", level="WARNING"):
                    collector.poll_once()
                state = collector.snapshot()
                self.assertEqual((state.api_success, state.lrt_valid), (1, 0))
                self.assertEqual(state.latest_restorable_timestamp_seconds, 700)
                self.assertEqual(state.last_valid_observation_timestamp_seconds, 1000)
                self.assertEqual(state.last_success_timestamp_seconds, 1060)
                self.assertEqual(state.invalid_lrt_total, 1)

    def test_wrong_identity_empty_multiple_and_malformed_responses_fail_closed(self):
        for bad in [response(identifier="other-db"), {"DBInstances": []},
                    {"DBInstances": response()["DBInstances"] * 2}, None]:
            with self.subTest(response=bad):
                collector = self.collector(bad)
                with self.assertLogs("rds_backup_exporter", level="WARNING"):
                    collector.poll_once()
                self.assertEqual(collector.snapshot().api_success, 0)
                self.assertIsNone(collector.snapshot().latest_restorable_timestamp_seconds)

    def test_factory_failure_is_observable_and_retried_next_poll(self):
        calls = []

        def factory(_):
            calls.append(1)
            if len(calls) == 1:
                raise RuntimeError("credential-secret")
            return FakeClient(response())

        collector = Collector(Settings("test-db", "ap-northeast-2"), factory, lambda: 1000)
        with self.assertLogs("rds_backup_exporter", level="WARNING"):
            collector.poll_once()
        collector.poll_once()
        self.assertEqual(collector.snapshot().lrt_valid, 1)
        self.assertEqual(len(calls), 2)

    def test_timer_accounts_for_call_duration_without_catchup_bursts(self):
        class StopAfter:
            def __init__(self, polls):
                self.polls = polls
                self.waits = []

            def is_set(self):
                return False

            def wait(self, seconds):
                self.waits.append(seconds)
                return len(self.waits) >= self.polls

        collector = self.collector(response(), response())
        clock = iter([100, 102, 164])
        stop = StopAfter(2)
        collector.run(stop, lambda: next(clock))
        self.assertEqual(stop.waits, [58, 56])
        collector = self.collector(response())
        clock = iter([100, 190])
        stop = StopAfter(1)
        collector.run(stop, lambda: next(clock))
        self.assertEqual(stop.waits, [60])

    def test_metrics_escape_labels_and_expose_fixed_contract(self):
        collector = self.collector(response())
        collector.settings = Settings("test-db", "ap-northeast-2", 'prod"\\\nline')
        collector.poll_once()
        text = render_metrics(collector).decode()
        self.assertIn('environment="prod\\"\\\\\\nline"', text)
        self.assertIn("# TYPE catchhole_rds_backup_poll_total counter", text)
        self.assertIn("# TYPE catchhole_rds_backup_lrt_valid gauge", text)
        self.assertIn("catchhole_rds_backup_latest_restorable_timestamp_seconds{", text)
        self.assertTrue(text.endswith("\n"))
        self.assertEqual(escape_label('a"b\\c\nd'), 'a\\"b\\\\c\\nd')

    def test_http_scrape_and_health_do_not_query_aws(self):
        collector = self.collector(response())
        server = make_server(collector, ("127.0.0.1", 0))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = f"http://127.0.0.1:{server.server_port}"
        try:
            for _ in range(2):
                with urlopen(base + "/metrics", timeout=2) as result:
                    self.assertEqual(result.status, 200)
                    self.assertIn("version=0.0.4", result.headers["Content-Type"])
                    self.assertIn(b"catchhole_rds_backup_api_success", result.read())
            with urlopen(base + "/healthz", timeout=2) as result:
                self.assertEqual(result.read(), b"ok\n")
            with self.assertRaises(HTTPError) as result:
                urlopen(base + "/unknown", timeout=2)
            self.assertEqual(result.exception.code, 404)
            result.exception.close()
            self.assertEqual(self.client.calls, [])
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def test_http_remains_responsive_during_blocked_api_call(self):
        entered, release = threading.Event(), threading.Event()

        class SlowClient:
            def describe_db_instances(self, **_):
                entered.set()
                release.wait(2)
                return response()

        collector = Collector(Settings("test-db", "ap-northeast-2"), lambda _: SlowClient(), lambda: 1000)
        poll = threading.Thread(target=collector.poll_once)
        poll.start()
        try:
            self.assertTrue(entered.wait(1))
            self.assertEqual(collector.snapshot().poll_total, 0)
            self.assertIn(b"catchhole_rds_backup_api_success", render_metrics(collector))
        finally:
            release.set()
            poll.join(2)
        self.assertEqual(collector.snapshot().poll_total, 1)


class SettingsTest(unittest.TestCase):
    def test_required_configuration_and_defaults(self):
        with patch.dict(os.environ, {"DB_IDENTIFIER": "test-db", "AWS_REGION": "ap-northeast-2"}, clear=True):
            self.assertEqual(Settings.from_environment(), Settings("test-db", "ap-northeast-2"))
        for bad in [{}, {"DB_IDENTIFIER": "test-db"},
                    {"POLL_INTERVAL_SECONDS": "NaN"}, {"POLL_INTERVAL_SECONDS": "1"},
                    {"LISTEN_PORT": "80"}, {"DB_IDENTIFIER": "bad--db"}]:
            values = {"DB_IDENTIFIER": "test-db", "AWS_REGION": "ap-northeast-2"}
            if not bad or bad == {"DB_IDENTIFIER": "test-db"}:
                values = bad
            else:
                values.update(bad)
            with self.subTest(values=values), patch.dict(os.environ, values, clear=True):
                with self.assertRaises(ValueError):
                    Settings.from_environment()


if __name__ == "__main__":
    unittest.main()
