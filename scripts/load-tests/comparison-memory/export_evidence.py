#!/usr/bin/env python3
"""Publish measured counts and SQL structure; never copy binds, state bodies, or synthetic IDs."""
import hashlib
import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path

artifact_root, summary_file, output_file = map(Path, sys.argv[1:])
summary = json.loads(summary_file.read_text())
sql_dictionary, profiles, profile_keys, phases = {}, {}, {}, []


def kind(sql):
    sql = sql.lower()
    if ' from world_setting_candidates ' in sql:
        if 'canonical_subject_key=?' in sql:
            return 'candidate_comparison_group'
        if ' where tbl.id in ' in sql:
            return 'candidate_follow_on_lock'
        if ' fetch first ' in sql:
            return 'candidate_claim_first'
        if '.review_status=?' in sql:
            return 'candidate_pending'
        return 'candidate_failed_status_check'
    if ' from analysis_jobs ' in sql:
        return 'job_run_predecessors' if '.analysis_run_id=?' in sql else 'job_lease_lock'
    if ' from analysis_job_episode_targets ' in sql:
        return 'job_episode_targets'
    if 'validation_reports' in sql:
        return 'validation_readiness'
    raise ValueError('Unclassified measured SQL; review it before exporting')


for result in summary['results']:
    label = result['directory']
    run_phases = []
    for sample in range(1, 4):
        for claim in range(1, 4):
            filename = f'sample-{sample}-claim-{claim}.json'
            raw = json.loads((artifact_root / label / filename).read_text())
            measured = defaultdict(lambda: {'executions': 0, 'consumedRows': 0})
            repeated_rows = 0
            for query in raw['queries']:
                query_kind = kind(query['sql'])
                columns = query['selectedColumns']
                key = (query_kind, tuple(columns))
                if key not in profile_keys:
                    profile_id = f'q{len(profiles) + 1:02}'
                    profile_keys[key] = profile_id
                    json_columns = [c for c in columns if c in ('automatic_input_state', 'state_journal')]
                    profile = {'kind': query_kind, 'selectedColumnCount': len(columns),
                               'selectedJobJsonColumns': json_columns,
                               'candidateResult': query_kind.startswith('candidate_')}
                    if query_kind in ('candidate_pending', 'candidate_comparison_group'):
                        sql_id = query_kind + ('_before' if json_columns else '_after')
                        sql_dictionary[sql_id] = {'sql': query['sql'],
                            'sha256': hashlib.sha256(query['sql'].encode()).hexdigest(),
                            'resultSetColumns': columns}
                        profile['sqlRef'] = sql_id
                    profiles[profile_id] = profile
                profile_id = profile_keys[key]
                profile = profiles[profile_id]
                assert query['failure'] is None
                if 'sqlRef' in profile:
                    assert sql_dictionary[profile['sqlRef']]['sql'] == query['sql']
                measured[profile_id]['executions'] += 1
                measured[profile_id]['consumedRows'] += query['consumedRows']
                if profile['candidateResult'] and profile['selectedJobJsonColumns']:
                    repeated_rows += query['consumedRows']
            assert repeated_rows == raw['candidateRowsSelectingJobJson']
            fixture = raw['fixture']
            assert repeated_rows * (fixture['inputBytes'] + fixture['journalBytes']) == raw['candidateLogicalJobJsonBytes']
            assert raw['httpStatus'] == 200
            phase = {'run': label, 'sample': sample, 'claim': claim, 'pendingCandidates': 60 - (claim - 1) * 20,
                'runtime': raw['runtime'], 'httpStatus': raw['httpStatus'],
                'httpResponseUtf8Bytes': raw['httpResponseUtf8Bytes'],
                'inputJsonbTextBytes': fixture['inputBytes'], 'journalJsonbTextBytes': fixture['journalBytes'],
                'candidateRowsSelectingJobJson': repeated_rows,
                'candidateLogicalJobJsonBytes': raw['candidateLogicalJobJsonBytes'], 'queriesByProfile': dict(measured)}
            phases.append(phase)
            run_phases.append(phase)
    for step in (1, 2, 3):
        rows = [p for p in run_phases if p['claim'] == step]
        expected = result['claim_steps'][str(step)]
        assert len(rows) == expected['samples']
        for key, metric, divisor in [('elapsedMillis', 'elapsed_ms', 1),
                ('sampledPeakHeapBytes', 'sampled_peak_heap_mib', 1024**2),
                ('allocatedBytes', 'allocated_mib', 1024**2), ('gcMillis', 'gc_ms', 1),
                ('processCpuNanos', 'process_cpu_ms', 1_000_000)]:
            values = [p['runtime'][key] / divisor for p in rows]
            actual = {'median': round(statistics.median(values), 2),
                      'min': round(min(values), 2), 'max': round(max(values), 2)}
            assert actual == expected[metric], (label, step, metric)

assert len(phases) == 27 and len(sql_dictionary) == 4
evidence = {'formatVersion': 1,
    'description': 'Observed 27 localhost HTTP claims. Profile row counts are JDBC ResultSet.next() counts; SQL has placeholders only.',
    'limitations': ['No state JSON, bound parameters, source text, or fixture IDs are exported.',
        'SQL dictionary contains only the four changed pending/group SELECT shapes; profiles cover every measured SELECT.',
        'Logical repeated JSON bytes are not network bytes, retained heap, or model input.',
        'Timing and sampled heap definitions are in gh365-comparison-memory.md.'],
    'uniqueSql': sql_dictionary, 'queryProfiles': profiles, 'phases': phases,
    'verification': {'phaseCount': 27, 'http200Count': 27, 'uniqueSqlCount': 4,
        'rawRowAndLogicalByteCalculationsMatch': True, 'allSummaryStageDistributionsMatch': True}}
output_file.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n')
print(f'Wrote {output_file}: {output_file.stat().st_size} bytes; 27 phases and 4 SQL shapes verified.')
