#!/usr/bin/env python3
"""Prepare one-DB Grafana alert rules; never contact AWS, Grafana or Discord."""

import argparse
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import re


PREFIX = "catchhole_rds_backup_"
JOB = "catchhole-rds-backup"


def nonempty(value):
    if not value.strip() or any(ord(char) < 32 for char in value):
        raise argparse.ArgumentTypeError("must be nonempty and contain no control characters")
    return value


def db_identifier(value):
    if not re.fullmatch(r"[a-zA-Z][a-zA-Z0-9-]{0,62}", value):
        raise argparse.ArgumentTypeError("expected an RDS DB instance identifier")
    if value.endswith("-") or "--" in value:
        raise argparse.ArgumentTypeError("DB identifier cannot end in '-' or contain '--'")
    return value


def region_name(value):
    if not re.fullmatch(r"[a-z]{2}(?:-[a-z]+)+-\d+", value):
        raise argparse.ArgumentTypeError("expected an AWS region, e.g. ap-northeast-2")
    return value


def expressions(db, region, environment, freshness_seconds=120):
    """Aggregate to one constant instance: unknown data must not resolve a lag alert."""
    labels = {"job": JOB, "environment": environment,
              "region": region, "db_instance_identifier": db}
    selector = ",".join(f"{key}={json.dumps(value)}" for key, value in labels.items())
    up_selector = f"job={json.dumps(JOB)},environment={json.dumps(environment)}"

    def metric(suffix):
        return f"max({PREFIX}{suffix}{{{selector}}})"

    lrt = metric("latest_restorable_timestamp_seconds")
    last_valid = metric("last_valid_observation_timestamp_seconds")
    gate = " and ".join([
        f"(max(up{{{up_selector}}}) == 1)",
        f"({metric('api_success')} == 1)",
        f"({metric('lrt_valid')} == 1)",
        f"(time() - {last_valid} >= 0)",
        f"(time() - {last_valid} <= {freshness_seconds})",
        f"({lrt} <= time())",
    ])
    lag = f"(time() - {lrt}) and ({gate})"
    # A 2m15s left-open range at 15s resolution contains nine points spanning
    # a full two minutes. Unknown observations count as zero in this history.
    sustained_warning = (
        f"min_over_time(((({lag}) > bool 600) or vector(0))[2m15s:15s]) == 1"
    )
    # No Grafana pending state: KeepLast can promote Pending during NoData.
    # Preserve an existing alert when high values return without enough new
    # history; only a fresh <=600 value supplies evidence for recovery.
    warning_lag = f"({lag}) and ((({lag}) <= 600) or ({sustained_warning}))"
    return {
        "lag_seconds": lag,
        "warning_lag_seconds": warning_lag,
        "collection_unhealthy": f"1 - (({gate}) or vector(0))",
    }


def extended_expressions(db, region, environment, max_connections, reserved_connections):
    """Independent source gates keep snapshot/resource failures out of LRT rules."""
    labels = {"job": JOB, "environment": environment,
              "region": region, "db_instance_identifier": db}
    selector = ",".join(f"{key}={json.dumps(value)}" for key, value in labels.items())
    up = f'max(up{{job={json.dumps(JOB)},environment={json.dumps(environment)}}}) == 1'

    def metric(namespace, suffix):
        return f"max(catchhole_rds_{namespace}_{suffix}{{{selector}}})"

    def age(timestamp, maximum):
        return f"(time() - {timestamp} >= 0) and (time() - {timestamp} <= {maximum})"

    def together(*conditions):
        return " and ".join(f"({condition})" for condition in conditions)

    def unhealthy(gate):
        return f"1 - ((vector(1) and ({gate})) or vector(0))"

    def sustained(value, comparator, threshold, gate):
        predicate = f"({value} {comparator} bool {threshold}) and ({gate})"
        # Left-open 5m15s / 15s has 21 points spanning a full five minutes.
        history = f"min_over_time((({predicate}) or vector(0))[5m15s:15s]) == 1"
        return f"({value}) and ({gate}) and ((({predicate}) == 0) or ({history}))"

    retention = metric("backup", "retention_days")
    allocated = metric("backup", "allocated_storage_bytes")
    window_start = metric("backup", "backup_window_start_seconds")
    window_end = metric("backup", "backup_window_end_seconds")
    duration = f"(({window_end} - {window_start} + 86400) % 86400)"
    metadata_gate = together(
        up, f"{metric('backup', 'api_success')} == 1",
        f"{metric('backup', 'metadata_valid')} == 1",
        age(metric("backup", "last_metadata_valid_observation_timestamp_seconds"), 120),
        f"{retention} >= 0", f"{retention} <= 35", f"{allocated} > 0",
        f"{window_start} >= 0", f"{window_start} < 86400",
        f"{window_end} >= 0", f"{window_end} < 86400", f"{duration} > 0",
    )
    # The most recently expired deadline, including a one-hour completion grace.
    # Before today's deadline yesterday's window remains due; crossing midnight
    # or opening the next window cannot clear an existing missing-backup alert.
    due_start = f"(floor((time() - {window_start} - {duration} - 3600) / 86400) * 86400 + {window_start})"
    has_available = metric("snapshot", "has_available")
    available_at = metric("snapshot", "latest_available_timestamp_seconds")
    snapshot_failed = metric("snapshot", "latest_snapshot_failed")
    snapshot_gate = together(
        up, f"{metric('snapshot', 'api_success')} == 1",
        f"{metric('snapshot', 'valid')} == 1",
        age(metric("snapshot", "last_valid_observation_timestamp_seconds"), 900),
        f"({has_available} == 0) or ({has_available} == 1)",
        f"({snapshot_failed} == 0) or ({snapshot_failed} == 1)",
        f"({has_available} == 0) or (({has_available} == 1) and ({available_at} <= time()))",
    )
    missing_snapshot = (
        f"((vector(1) and ({has_available} == 0)) "
        f"or (({available_at} < bool {due_start}) and ({has_available} == 1)))"
    )

    resource_gates = {}
    resource_values = {}
    for name in ("cpu_percent", "free_storage_bytes", "database_connections"):
        resource_values[name] = metric("resource", name)
        resource_gates[name] = together(
            up, f"{metric('resource', name + '_api_success')} == 1",
            f"{metric('resource', name + '_valid')} == 1",
            age(metric("resource", name + "_last_success_timestamp_seconds"), 120),
            age(metric("resource", name + "_source_timestamp_seconds"), 300),
            f"{resource_values[name]} >= 0",
            *([f"{resource_values[name]} <= 100"] if name == "cpu_percent" else []),
        )
    storage_gate = together(resource_gates["free_storage_bytes"], metadata_gate)
    storage_threshold = f"clamp_min({allocated} * 0.2, 5368709120)"
    connection_threshold = (max_connections - reserved_connections) * 0.8
    return {
        "retention_disabled": f"({retention} == bool 0) and ({metadata_gate})",
        "retention_below_policy": f"(({retention} >= bool 1) * ({retention} < bool 7)) and ({metadata_gate})",
        "retention_above_policy": f"({retention} > bool 7) and ({metadata_gate})",
        "metadata_collection_unhealthy": unhealthy(metadata_gate),
        "snapshot_due_window_start_seconds": f"{due_start} and ({metadata_gate})",
        "snapshot_overdue": f"{missing_snapshot} and ({snapshot_gate}) and ({metadata_gate})",
        "snapshot_latest_failed": f"{snapshot_failed} and ({snapshot_gate})",
        "snapshot_collection_unhealthy": unhealthy(snapshot_gate),
        "resource_cpu_percent": sustained(resource_values["cpu_percent"], ">", "80", resource_gates["cpu_percent"]),
        "resource_free_storage_bytes": sustained(resource_values["free_storage_bytes"], "<", storage_threshold, storage_gate),
        "resource_storage_threshold_bytes": f"{storage_threshold} and ({storage_gate})",
        "resource_database_connections": sustained(resource_values["database_connections"], ">=", str(connection_threshold), resource_gates["database_connections"]),
        "resource_collection_unhealthy": unhealthy(together(*resource_gates.values())),
    }


def rule(uid, title, expression, threshold, pending, severity, config, health=False):
    return {
        "uid": uid,
        "title": title,
        "orgID": config.org_id,
        "folderUID": config.folder_uid,
        "ruleGroup": "catchhole-rds-backup",
        "condition": "B",
        "data": [
            {
                "refId": "A", "queryType": "",
                "relativeTimeRange": {"from": 300, "to": 0},
                "datasourceUid": config.datasource_uid,
                "model": {
                    "datasource": {"type": "prometheus", "uid": config.datasource_uid},
                    "editorMode": "code", "expr": expression,
                    "instant": True, "range": False,
                    "intervalMs": 1000, "maxDataPoints": 43200, "refId": "A",
                },
            },
            {
                "refId": "B", "queryType": "",
                "relativeTimeRange": {"from": 0, "to": 0},
                "datasourceUid": "__expr__",
                "model": {
                    "datasource": {"type": "__expr__", "uid": "__expr__"},
                    "type": "math", "expression": f"$A > {threshold}",
                    "intervalMs": 1000, "maxDataPoints": 43200, "refId": "B",
                },
            },
        ],
        "noDataState": "Alerting" if health else "KeepLast",
        "execErrState": "Alerting" if health else "KeepLast",
        "for": pending,
        "keep_firing_for": "0s",
        "isPaused": not config.activate,
        "labels": {
            "service": "rds-backup", "severity": severity,
            "environment": config.environment, "region": config.region,
            "db_instance_identifier": config.db_instance_identifier,
        },
        "annotations": {
            "summary": title,
            "description": (
                "AWS 조회·필수 값·최근 수집·Prometheus 연결 중 하나를 신뢰할 수 없습니다. "
                "수집 장애와 실제 백업 지연을 구분해야 합니다."
                if health else
                "최근 정상 조회의 LatestRestorableTime 기준 지연입니다. "
                "데이터 유실 확정이나 실제 복원 성공을 뜻하지 않습니다."
            ),
            "action": (
                "디스코드에 대응 시작을 남긴 뒤 exporter up, API 성공, IAM 권한, "
                "마지막 유효 수집 시각, Prometheus 연결을 확인하세요."
                if health else
                "디스코드에 대응 시작을 남긴 뒤 RDS의 복원 가능 시각·자동 백업 설정·"
                "백업 이벤트를 확인하세요. 알림만 보고 DB를 삭제하거나 복원하지 마세요."
            ),
        },
        "notification_settings": {
            "receiver": config.contact_point,
            "group_wait": "0s", "group_interval": "1m", "repeat_interval": "15m",
            "mute_time_intervals": [], "active_time_intervals": [],
        },
    }


def build(config):
    queries = expressions(config.db_instance_identifier, config.region, config.environment)
    identity = f"{config.environment}/{config.region}/{config.db_instance_identifier}"
    suffix = hashlib.sha256(identity.encode()).hexdigest()[:10]
    rules = [
        rule(f"rds-lrt-warning-{suffix}", "RDS 백업 복원 가능 시각 10분 초과",
             queries["warning_lag_seconds"], 600, "0s", "warning", config),
        rule(f"rds-lrt-critical-{suffix}", "RDS 백업 복원 가능 시각 15분 초과",
             queries["lag_seconds"], 900, "0s", "critical", config),
        rule(f"rds-collection-{suffix}", "RDS 백업 감시 수집 상태 확인 필요",
             queries["collection_unhealthy"], 0, "2m", "warning", config, health=True),
    ]
    include_extended = getattr(config, "include_extended", False)
    max_connections = getattr(config, "max_connections", None)
    reserved_connections = getattr(config, "reserved_connections", None)
    if include_extended:
        if (type(max_connections) is not int or type(reserved_connections) is not int
                or max_connections <= 0 or not 0 <= reserved_connections < max_connections):
            raise ValueError("extended rules require measured --max-connections > --reserved-connections >= 0")
        queries.update(extended_expressions(config.db_instance_identifier, config.region,
                                           config.environment, max_connections, reserved_connections))
        definitions = [
            ("retention-disabled", "RDS 자동 백업 비활성", "retention_disabled", "critical", False,
             "보존 기간이 0일입니다. 자동 백업 설정과 변경 이력을 확인하세요."),
            ("retention-short", "RDS 자동 백업 보존 7일 미만", "retention_below_policy", "warning", False,
             "보존 기간이 1~6일로 정책 7일보다 짧습니다. 승인된 변경인지 확인하세요."),
            ("retention-long", "RDS 자동 백업 보존 7일 초과", "retention_above_policy", "info", False,
             "보존 기간이 정책 7일보다 깁니다. 보호 약화를 뜻하지 않으며 변경 사유와 비용을 확인하세요."),
            ("metadata-health", "RDS 백업 설정 수집 상태 확인 필요", "metadata_collection_unhealthy", "warning", True,
             "DB 설정 조회·필수 값·120초 이내 유효 관측을 확인하세요. LRT 유효성과 별도로 판단합니다."),
            ("snapshot-overdue", "RDS 일일 자동 스냅샷 완료 확인 지연", "snapshot_overdue", "warning", False,
             "가장 최근 마감된 백업 창의 시작 이후 생성된 available 자동 스냅샷이 없습니다. 마감은 창 종료+1시간입니다. 생성 시각은 완료 시각이 아닙니다."),
            ("snapshot-failed", "RDS 최신 자동 스냅샷 실패 상태", "snapshot_latest_failed", "warning", False,
             "생성 시각이 있는 최신 완료 상태(available/failed)가 failed입니다. 생성 중 상태는 이전 실패를 해제하지 않습니다. 일일 available 스냅샷 확보 여부도 함께 확인하세요."),
            ("snapshot-health", "RDS 스냅샷 수집 상태 확인 필요", "snapshot_collection_unhealthy", "warning", True,
             "스냅샷 API·필수 값·900초 이내 유효 관측을 확인하세요. 조회 실패를 백업 없음으로 바꾸지 않습니다."),
            ("cpu-high", "RDS CPU 80% 초과 5분 지속", "resource_cpu_percent", "warning", False,
             "유효한 CPU 관측값이 80% 초과로 5분 유지됐습니다. 쿼리·부하를 확인하세요."),
            ("storage-low", "RDS 저장 공간 여유 부족 5분 지속", "resource_free_storage_bytes", "warning", False,
             "유효한 남은 공간이 max(할당량 20%, 5GiB) 미만으로 5분 유지됐습니다. A는 남은 바이트, C는 기준 바이트입니다."),
            ("connections-high", "RDS 일반 연결 예산 80% 이상 5분 지속", "resource_database_connections", "warning", False,
             f"유효한 연결 수가 ({max_connections}-{reserved_connections})*0.8={(max_connections-reserved_connections)*0.8:g} 이상으로 5분 유지됐습니다. 최대·예약 연결 설정을 바꾸면 이 규칙도 다시 생성해야 합니다."),
            ("resource-health", "RDS 자원 지표 수집 상태 확인 필요", "resource_collection_unhealthy", "warning", True,
             "CPU·공간·연결 수 각각의 API 성공·유효성·원천 시각 300초·조회 성공 시각 120초 이내 여부를 확인하세요."),
        ]
        for name, title, query, severity, health, description in definitions:
            item = rule(f"rds-{name}-{suffix}", title, queries[query], 0,
                        "2m" if health else "0s", severity, config, health=health)
            item["annotations"]["description"] = description
            item["annotations"]["action"] = (
                "채널에 대응 시작을 남기고 해당 AWS 값과 수집 상태를 대조하세요. "
                "이 알림은 실제 복원 성공·RPO/RTO 달성을 증명하지 않습니다."
            )
            if name == "cpu-high":
                item["data"][1]["model"]["expression"] = "$A > 80"
            elif name == "connections-high":
                item["data"][1]["model"]["expression"] = f"$A >= {(max_connections - reserved_connections) * 0.8}"
            elif name == "storage-low":
                threshold_query = deepcopy(item["data"][0])
                threshold_query["refId"] = threshold_query["model"]["refId"] = "C"
                threshold_query["model"]["expr"] = queries["resource_storage_threshold_bytes"]
                item["data"].append(threshold_query)
                item["data"][1]["model"]["expression"] = "$A < $C"
            rules.append(item)
    elif max_connections is not None or reserved_connections is not None:
        raise ValueError("connection limits require --include-extended")
    return {
        "title": "catchhole-rds-backup", "folderUid": config.folder_uid,
        "interval": 60, "rules": rules,
    }, queries


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--db-instance-identifier", required=True, type=db_identifier)
    result.add_argument("--region", required=True, type=region_name)
    result.add_argument("--environment", default="prod", type=nonempty)
    result.add_argument("--folder-uid", required=True, type=nonempty)
    result.add_argument("--contact-point", required=True, type=nonempty,
                        help="existing Grafana contact point name, never a webhook URL")
    result.add_argument("--datasource-uid", default="catchhole-prometheus", type=nonempty)
    result.add_argument("--org-id", default=1, type=int)
    result.add_argument("--include-extended", action="store_true",
                        help="include retention, automated snapshot and RDS resource rules")
    result.add_argument("--max-connections", type=int, help="measured PostgreSQL max_connections")
    result.add_argument("--reserved-connections", type=int,
                        help="measured total reserved_connections + superuser_reserved_connections")
    result.add_argument("--output-dir", required=True, type=Path)
    result.add_argument("--activate", action="store_true",
                        help="generate unpaused rules only after reviewing thresholds and routing")
    return result


def main():
    cli = parser()
    config = cli.parse_args()
    if config.org_id < 1:
        cli.error("--org-id must be positive")
    if "://" in config.contact_point:
        cli.error("--contact-point must be a Grafana name, not a URL")
    try:
        group, queries = build(config)
    except ValueError as error:
        cli.error(str(error))
    config.output_dir.mkdir(parents=True, exist_ok=True)
    for filename, payload in [("rule-group.json", group), ("queries.json", queries)]:
        (config.output_dir / filename).write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Prepared {len(group['rules'])} rules; paused={not config.activate}. No remote changes.")


if __name__ == "__main__":
    main()
