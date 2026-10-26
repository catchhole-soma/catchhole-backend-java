import argparse
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

import generate


def configuration(*extra):
    return generate.parser().parse_args([
        "--db-instance-identifier", "test-db", "--region", "ap-northeast-2",
        "--folder-uid", "test-folder", "--contact-point", "local-test-only",
        "--output-dir", "/tmp/not-written", *extra,
    ])


class GeneratorTests(unittest.TestCase):
    def test_paused_rules_have_stable_instances_and_safe_failure_handling(self):
        group, _ = generate.build(configuration())
        warning, critical, health = group["rules"]
        self.assertEqual(60, group["interval"])
        self.assertTrue(all(rule["isPaused"] for rule in group["rules"]))
        self.assertEqual(["0s", "0s", "2m"], [rule["for"] for rule in group["rules"]])
        self.assertEqual("$A > 600", warning["data"][1]["model"]["expression"])
        self.assertEqual("$A > 900", critical["data"][1]["model"]["expression"])
        for rule in [warning, critical]:
            self.assertEqual("KeepLast", rule["noDataState"])
            self.assertEqual("KeepLast", rule["execErrState"])
        self.assertEqual("Alerting", health["execErrState"])
        self.assertEqual("test-db", health["labels"]["db_instance_identifier"])
        for rule in group["rules"]:
            self.assertEqual("0s", rule["keep_firing_for"])
            self.assertNotIn("keepFiringFor", rule)
            self.assertEqual({
                "receiver": "local-test-only",
                "group_wait": "0s", "group_interval": "1m", "repeat_interval": "15m",
                "mute_time_intervals": [], "active_time_intervals": [],
            }, rule["notification_settings"])

    def test_activation_requires_explicit_flag(self):
        group, _ = generate.build(configuration("--activate"))
        self.assertFalse(any(rule["isPaused"] for rule in group["rules"]))

    def test_invalid_identity_is_rejected(self):
        for value in ["", "x--y", "x-", 'db"} or vector(1)']:
            with self.assertRaises(argparse.ArgumentTypeError):
                generate.db_identifier(value)
        with self.assertRaises(argparse.ArgumentTypeError):
            generate.region_name('ap-northeast-2"}')
        with self.assertRaises(argparse.ArgumentTypeError):
            generate.nonempty("bad\nname")

    def test_extended_rules_preserve_existing_rules_and_measured_limits(self):
        original, _ = generate.build(configuration())
        extended, queries = generate.build(configuration(
            "--include-extended", "--max-connections", "181", "--reserved-connections", "5"))
        self.assertEqual(original["rules"], extended["rules"][:3])
        self.assertEqual(14, len(extended["rules"]))
        self.assertEqual(14, len({rule["uid"] for rule in extended["rules"]}))
        by_uid = {rule["uid"].rsplit("-", 1)[0]: rule for rule in extended["rules"]}
        self.assertEqual("$A >= 140.8", by_uid["rds-connections-high"]["data"][1]["model"]["expression"])
        storage = by_uid["rds-storage-low"]
        self.assertEqual("$A < $C", storage["data"][1]["model"]["expression"])
        self.assertEqual(queries["resource_storage_threshold_bytes"], storage["data"][2]["model"]["expr"])
        for rule in extended["rules"][3:]:
            health = "-health-" in rule["uid"]
            self.assertEqual("2m" if health else "0s", rule["for"])
            self.assertEqual("Alerting" if health else "KeepLast", rule["noDataState"])
            self.assertEqual("Alerting" if health else "KeepLast", rule["execErrState"])
            self.assertTrue(rule["isPaused"])

    def test_extended_limits_must_be_explicit_and_valid(self):
        invalid = [
            ("--include-extended",),
            ("--include-extended", "--max-connections", "181"),
            ("--include-extended", "--max-connections", "181", "--reserved-connections", "-1"),
            ("--include-extended", "--max-connections", "181", "--reserved-connections", "181"),
            ("--max-connections", "181", "--reserved-connections", "5"),
        ]
        for arguments in invalid:
            with self.subTest(arguments=arguments), self.assertRaises(ValueError):
                generate.build(configuration(*arguments))


class PromQLTests(unittest.TestCase):
    """Use the deployed Prometheus version's actual parser and evaluator."""

    def run_promtool(self, tests):
        with tempfile.TemporaryDirectory(prefix="rds-alert-promql-") as directory:
            path = Path(directory) / "tests.json"
            path.write_text(json.dumps({"rule_files": [], "tests": tests}), encoding="utf-8")
            result = subprocess.run([
                "docker", "run", "--rm", "--network", "none",
                "--mount", f"type=bind,source={directory},target=/tests,readonly",
                "--entrypoint", "/bin/promtool", "prom/prometheus:v3.13.3",
                "test", "rules", "/tests/tests.json",
            ], capture_output=True, text=True, timeout=60)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_boundaries_and_collection_failure_modes(self):
        queries = generate.expressions("test-db", "ap-northeast-2", "prod")
        metrics = {
            "up": 1, "api_success": 1, "lrt_valid": 1,
            "latest_restorable_timestamp_seconds": 3300,
            "last_valid_observation_timestamp_seconds": 3600,
        }

        def case(name, updates=None, lag=300, unhealthy=0, missing=False):
            current = dict(metrics)
            current.update(updates or {})
            series = []
            for metric, value in current.items():
                labels = 'job="catchhole-rds-backup",environment="prod"'
                if metric != "up":
                    metric = generate.PREFIX + metric
                    labels += ',region="ap-northeast-2",db_instance_identifier="test-db"'
                series.append({"series": f"{metric}{{{labels}}}", "values": f"{value}+0x60"})

            def samples(value):
                return [] if value is None else [{"labels": "{}", "value": value}]

            checks = [
                (queries["lag_seconds"], lag),
                (queries["collection_unhealthy"], unhealthy),
                (f'({queries["lag_seconds"]}) > bool 600', None if lag is None else int(lag > 600)),
                (f'({queries["lag_seconds"]}) > bool 900', None if lag is None else int(lag > 900)),
            ]
            return {
                "name": name, "interval": "1m", "input_series": [] if missing else series,
                "promql_expr_test": [
                    {"expr": expr, "eval_time": "1h", "exp_samples": samples(value)}
                    for expr, value in checks
                ],
            }

        tests = [
            case("normal"),
            case("warning-boundary", {"latest_restorable_timestamp_seconds": 3000}, lag=600),
            case("warning-crossed", {"latest_restorable_timestamp_seconds": 2999}, lag=601),
            case("critical-boundary-warning-remains", {"latest_restorable_timestamp_seconds": 2700}, lag=900),
            case("critical-and-warning-both-active", {"latest_restorable_timestamp_seconds": 2699}, lag=901),
            case("failed-API-with-old-cached-LRT", {"api_success": 0}, lag=None, unhealthy=1),
            case("invalid-LRT-with-cache", {"lrt_valid": 0}, lag=None, unhealthy=1),
            case("exporter-down", {"up": 0}, lag=None, unhealthy=1),
            case("stopped-poll-loop", {"last_valid_observation_timestamp_seconds": 3479}, lag=None, unhealthy=1),
            case("future-observation-clock", {"last_valid_observation_timestamp_seconds": 3601}, lag=None, unhealthy=1),
            case("future-LRT-clock", {"latest_restorable_timestamp_seconds": 3601}, lag=None, unhealthy=1),
            case("all-series-missing", missing=True, lag=None, unhealthy=1),
        ]
        self.run_promtool(tests)

    def test_warning_requires_full_valid_history_and_preserves_unknown(self):
        warning = generate.expressions("test-db", "ap-northeast-2", "prod")["warning_lag_seconds"]

        def scenario(name, failed_from, resumed_at, checks):
            values = {suffix: [] for suffix in [
                "api_success", "lrt_valid", "latest_restorable_timestamp_seconds",
                "last_valid_observation_timestamp_seconds",
            ]}
            last_valid = last_lrt = 0
            for now in range(0, 2401, 15):
                valid = not (failed_from <= now < resumed_at)
                lag = 601 if 1800 <= now < 2280 else 300
                if valid:
                    last_valid, last_lrt = now, now - lag
                values["api_success"].append(int(valid))
                values["lrt_valid"].append(int(valid))
                values["latest_restorable_timestamp_seconds"].append(last_lrt)
                values["last_valid_observation_timestamp_seconds"].append(last_valid)
            labels = 'job="catchhole-rds-backup",environment="prod"'
            series = [{"series": f"up{{{labels}}}", "values": "1+0x160"}]
            labels += ',region="ap-northeast-2",db_instance_identifier="test-db"'
            series += [
                {"series": f"{generate.PREFIX}{suffix}{{{labels}}}",
                 "values": " ".join(map(str, samples))}
                for suffix, samples in values.items()
            ]
            return {
                "name": name, "interval": "15s", "input_series": series,
                "promql_expr_test": [
                    {"expr": warning, "eval_time": at,
                     "exp_samples": [] if value is None else [{"labels": "{}", "value": value}]}
                    for at, value in checks
                ],
            }

        self.run_promtool([
            scenario("full-two-minute-boundary", 9999, 9999, [
                ("29m", 300), ("30m", None), ("31m45s", None), ("31m59s", None),
                ("32m", 601), ("38m", 300),
            ]),
            scenario("one-breach-then-failure-cannot-promote", 1860, 1980, [
                ("30m", None), ("31m", None), ("32m", None),
                ("33m", None), ("34m45s", None), ("35m", 601),
            ]),
            scenario("previously-firing-unknown-and-high-resume-stay-empty", 1980, 2100, [
                ("32m", 601), ("33m", None), ("34m", None),
                ("35m", None), ("36m45s", None), ("37m", 601), ("38m", 300),
            ]),
        ])

    def extended_series(self, overrides=None, interval=15, count=240):
        """Independent source timestamps advance with the actual fixture clock."""
        values = {
            "backup_api_success": 1, "backup_metadata_valid": 1,
            "backup_last_metadata_valid_observation_timestamp_seconds": f"0+{interval}x{count}",
            "backup_retention_days": 7, "backup_allocated_storage_bytes": 30 * 1024**3,
            "backup_backup_window_start_seconds": 0, "backup_backup_window_end_seconds": 1800,
            "snapshot_api_success": 1, "snapshot_valid": 1,
            "snapshot_last_valid_observation_timestamp_seconds": f"0+{interval}x{count}",
            "snapshot_has_available": 1, "snapshot_latest_available_timestamp_seconds": 300,
            "snapshot_latest_snapshot_failed": 0,
            "resource_cpu_percent": 30, "resource_free_storage_bytes": 10 * 1024**3,
            "resource_database_connections": 20,
        }
        for name in ("cpu_percent", "free_storage_bytes", "database_connections"):
            for suffix in ("api_success", "valid"):
                values[f"resource_{name}_{suffix}"] = 1
            for suffix in ("last_success_timestamp_seconds", "source_timestamp_seconds"):
                values[f"resource_{name}_{suffix}"] = f"0+{interval}x{count}"
        values.update(overrides or {})
        labels = 'job="catchhole-rds-backup",environment="prod"'
        series = [{"series": f"up{{{labels}}}", "values": f"1+0x{count}"}]
        labels += ',region="ap-northeast-2",db_instance_identifier="test-db"'
        series.extend(
            {"series": f"catchhole_rds_{name}{{{labels}}}",
             "values": value if isinstance(value, str) else f"{value}+0x{count}"}
            for name, value in values.items() if value is not None
        )
        return series

    def test_extended_retention_snapshot_and_resource_boundaries(self):
        queries = generate.extended_expressions("test-db", "ap-northeast-2", "prod", 181, 5)
        tests = []

        def case(name, updates, checks):
            tests.append({
                "name": name, "interval": "15s", "input_series": self.extended_series(updates),
                "promql_expr_test": [
                    {"expr": queries[key] if key in queries else key, "eval_time": "1h",
                     "exp_samples": [] if value is None else [{"labels": "{}", "value": value}]}
                    for key, value in checks.items()
                ],
            })

        for days in (0, 1, 6, 7, 8, 35):
            case(f"retention-{days}-independent-of-invalid-LRT", {
                "backup_retention_days": days, "backup_lrt_valid": 0,
            }, {
                "retention_disabled": int(days == 0), "retention_below_policy": int(1 <= days < 7),
                "retention_above_policy": int(days > 7), "metadata_collection_unhealthy": 0,
            })
        case("metadata-missing-with-otherwise-fresh-LRT", {"backup_metadata_valid": 0}, {
            "retention_disabled": None, "metadata_collection_unhealthy": 1,
            "snapshot_overdue": None, "snapshot_collection_unhealthy": 0,
            "resource_free_storage_bytes": None, "resource_cpu_percent": 30,
        })
        case("metadata-stale", {"backup_last_metadata_valid_observation_timestamp_seconds": 3479}, {
            "metadata_collection_unhealthy": 1, "retention_above_policy": None,
        })
        case("snapshot-no-available-is-real-missing-not-query-failure", {
            "snapshot_has_available": 0, "snapshot_latest_available_timestamp_seconds": None,
        }, {"snapshot_overdue": 1, "snapshot_collection_unhealthy": 0})
        case("snapshot-failed", {"snapshot_latest_snapshot_failed": 1}, {"snapshot_latest_failed": 1})
        case("snapshot-API-failure-preserves-cache-as-unknown", {"snapshot_api_success": 0}, {
            "snapshot_overdue": None, "snapshot_latest_failed": None, "snapshot_collection_unhealthy": 1,
            "retention_disabled": 0, "resource_collection_unhealthy": 0,
        })
        case("snapshot-stale", {"snapshot_last_valid_observation_timestamp_seconds": 2699}, {
            "snapshot_overdue": None, "snapshot_collection_unhealthy": 1,
        })
        case("snapshot-required-field-missing", {"snapshot_has_available": None}, {
            "snapshot_overdue": None, "snapshot_collection_unhealthy": 1,
        })
        case("snapshot-future-available-time", {"snapshot_latest_available_timestamp_seconds": 3601}, {
            "snapshot_overdue": None, "snapshot_collection_unhealthy": 1,
        })
        for cpu in (80, 80.01):
            case(f"CPU-boundary-{cpu}", {"resource_cpu_percent": cpu}, {
                f'({queries["resource_cpu_percent"]}) > bool 80': int(cpu > 80),
            })
        for connections in (140, 141):
            case(f"connections-boundary-{connections}", {"resource_database_connections": connections}, {
                f'({queries["resource_database_connections"]}) >= bool 140.8': int(connections >= 140.8),
            })
        for allocated, threshold in [(30 * 1024**3, 6 * 1024**3), (10 * 1024**3, 5 * 1024**3)]:
            for free in (threshold, threshold - 1, 0):
                case(f"storage-boundary-{allocated}-{free}", {
                    "backup_allocated_storage_bytes": allocated, "resource_free_storage_bytes": free,
                }, {
                    "resource_storage_threshold_bytes": threshold,
                    f'({queries["resource_free_storage_bytes"]}) < bool ({queries["resource_storage_threshold_bytes"]})': int(free < threshold),
                })
        case("resource-one-source-failure-does-not-hide-other-metrics", {
            "resource_cpu_percent_api_success": 0,
        }, {"resource_cpu_percent": None, "resource_free_storage_bytes": 10 * 1024**3,
            "resource_database_connections": 20, "resource_collection_unhealthy": 1})
        case("resource-CloudWatch-source-stale", {"resource_cpu_percent_source_timestamp_seconds": 3299}, {
            "resource_cpu_percent": None, "resource_collection_unhealthy": 1,
        })
        case("resource-API-cache-stale", {"resource_cpu_percent_last_success_timestamp_seconds": 3479}, {
            "resource_cpu_percent": None, "resource_collection_unhealthy": 1,
        })
        case("resource-source-future", {"resource_cpu_percent_source_timestamp_seconds": 3601}, {
            "resource_cpu_percent": None, "resource_collection_unhealthy": 1,
        })
        self.run_promtool(tests)

    def test_snapshot_deadline_uses_latest_expired_window_and_crosses_midnight(self):
        queries = generate.extended_expressions("test-db", "ap-northeast-2", "prod", 181, 5)

        def scenario(name, start, end, available, points):
            updates = {
                "backup_backup_window_start_seconds": start, "backup_backup_window_end_seconds": end,
                "snapshot_latest_available_timestamp_seconds": available,
            }
            return {
                "name": name, "interval": "1m",
                "input_series": self.extended_series(updates, interval=60, count=5000),
                "promql_expr_test": [
                    {"expr": queries[key], "eval_time": f"{at}s", "exp_samples": [{"labels": "{}", "value": value}]}
                    for at, due, overdue in points
                    for key, value in [("snapshot_due_window_start_seconds", due), ("snapshot_overdue", overdue)]
                ],
            }

        self.run_promtool([
            scenario("seoul-window-keeps-yesterday-valid-until-deadline", 59700, 61500, 60100, [
                (86400 + 65099, 59700, 0),
                (86400 + 65100, 86400 + 59700, 1),
                (172800 + 59700, 86400 + 59700, 1),
                (172800 + 61500, 86400 + 59700, 1),
            ]),
            scenario("UTC-midnight-crossing-window", 85800, 1200, 400, [
                (91199, -600, 0), (91200, 85800, 1),
            ]),
            scenario("completed-new-snapshot-clears-missing", 85800, 1200, 86000, [
                (91200, 85800, 0),
            ]),
        ])

    def test_resource_five_minutes_failure_and_recovery_transitions(self):
        queries = generate.extended_expressions("test-db", "ap-northeast-2", "prod", 181, 5)
        normal = {"cpu_percent": 30, "free_storage_bytes": 10 * 1024**3, "database_connections": 20}
        high = {"cpu_percent": 90, "free_storage_bytes": 5 * 1024**3, "database_connections": 150}

        def scenario(name, failure_start, resume, points):
            overrides = {}
            for resource in normal:
                values = []
                success = []
                timestamps = []
                last_value, last_success = normal[resource], 0
                for now in range(0, 3001, 15):
                    valid = not failure_start <= now < resume
                    if valid:
                        last_value = high[resource] if 1800 <= now < 2700 else normal[resource]
                        last_success = now
                    values.append(last_value)
                    success.append(int(valid))
                    timestamps.append(last_success)
                overrides[f"resource_{resource}"] = " ".join(map(str, values))
                for suffix in ("api_success", "valid"):
                    overrides[f"resource_{resource}_{suffix}"] = " ".join(map(str, success))
                for suffix in ("source_timestamp_seconds", "last_success_timestamp_seconds"):
                    overrides[f"resource_{resource}_{suffix}"] = " ".join(map(str, timestamps))
            return {
                "name": name, "interval": "15s", "input_series": self.extended_series(overrides, count=200),
                "promql_expr_test": [
                    {"expr": queries["resource_" + resource], "eval_time": at,
                     "exp_samples": [] if state is None else [{"labels": "{}", "value": (high if state == "high" else normal)[resource]}]}
                    for at, state in points for resource in normal
                ],
            }

        self.run_promtool([
            scenario("five-full-minutes-boundary", 9999, 9999, [
                ("29m", "normal"), ("30m", None), ("34m45s", None), ("34m59s", None),
                ("35m", "high"), ("45m", "normal"),
            ]),
            scenario("failed-observation-cannot-create-new-resource-alert", 1860, 1980, [
                ("30m", None), ("32m", None), ("33m", None), ("35m", None),
                ("37m45s", None), ("38m", "high"),
            ]),
            scenario("firing-stays-unknown-through-failure-and-high-resume", 2160, 2280, [
                ("35m", "high"), ("36m", None), ("38m", None),
                ("42m45s", None), ("43m", "high"), ("45m", "normal"),
            ]),
        ])


if __name__ == "__main__":
    unittest.main()
