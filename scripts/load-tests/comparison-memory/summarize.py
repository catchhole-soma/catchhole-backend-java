#!/usr/bin/env python3
"""Read synthetic measurements; distinguish observed heap from logical JSON result bytes."""
import json
import statistics
import sys
from pathlib import Path

MIB = 1024 * 1024


def median(values):
    return round(statistics.median(values), 2) if values else None


def distribution(values, divisor=1):
    if not values:
        return None
    return {'median': median([v / divisor for v in values]),
            'min': round(min(values) / divisor, 2), 'max': round(max(values) / divisor, 2)}


def summarize(directory):
    path = Path(directory)
    fixtures = [json.loads(p.read_text()) for p in sorted(path.glob('sample-*-fixture.json'))]
    claims = [json.loads(p.read_text()) for p in sorted(path.glob('sample-*-claim-*.json')) if not p.name.endswith('-started.json')]
    parity = [json.loads(p.read_text()) for p in sorted(path.glob('sample-*-parity.json'))]
    first = [json.loads(p.read_text()) for p in sorted(path.glob('sample-*-claim-1.json'))]
    steps = {}
    for step in (1, 2, 3):
        rows = [json.loads(p.read_text()) for p in sorted(path.glob(f'sample-*-claim-{step}.json'))]
        steps[str(step)] = {
            'pending_candidates': 60 - (step - 1) * 20,
            'samples': len(rows),
            'elapsed_ms': distribution([r['runtime']['elapsedMillis'] for r in rows]),
            'sampled_peak_heap_mib': distribution([r['runtime']['sampledPeakHeapBytes'] for r in rows], MIB),
            'allocated_mib': distribution([r['runtime']['allocatedBytes'] for r in rows], MIB),
            'gc_ms': distribution([r['runtime']['gcMillis'] for r in rows]),
            'process_cpu_ms': distribution([r['runtime']['processCpuNanos'] for r in rows], 1_000_000),
        }
    return {
        'directory': str(path),
        'exit_code': (path / 'exit-code.txt').read_text().strip() if (path / 'exit-code.txt').exists() else None,
        'fixture_count': len(fixtures), 'successful_claims': sum(c['httpStatus'] == 200 for c in claims),
        'parity_samples': len(parity),
        'input_jsonb_text_bytes': [f['fixture']['inputBytes'] for f in fixtures],
        'journal_jsonb_text_bytes': [f['fixture']['journalBytes'] for f in fixtures],
        'first_claim_elapsed_ms_median': median([c['runtime']['elapsedMillis'] for c in first]),
        'first_claim_sampled_peak_heap_mib': [round(c['runtime']['sampledPeakHeapBytes'] / MIB, 2) for c in first],
        'first_claim_allocated_mib': [round(c['runtime']['allocatedBytes'] / MIB, 2) for c in first],
        'first_claim_gc_ms': [c['runtime']['gcMillis'] for c in first],
        'first_claim_candidate_rows_selecting_job_json': [c['candidateRowsSelectingJobJson'] for c in first],
        'first_claim_logical_job_json_mib': [round(c['candidateLogicalJobJsonBytes'] / MIB, 2) for c in first],
        'append_elapsed_ms_median': median([f['appendRuntime']['elapsedMillis'] for f in fixtures]),
        'append_allocated_mib': [round(f['appendRuntime']['allocatedBytes'] / MIB, 2) for f in fixtures],
        'append_gc_ms': [f['appendRuntime']['gcMillis'] for f in fixtures],
        'append_process_cpu_ms': distribution([f['appendRuntime']['processCpuNanos'] for f in fixtures], 1_000_000),
        'claim_steps': steps,
        'response_hashes': sorted({p['responseSemanticHash'] for p in parity}),
        'history_evidence_hashes': sorted({p['finalHistoryAndEvidenceHash'] for p in parity}),
    }


results = [summarize(directory) for directory in sys.argv[1:]]
print(json.dumps(results, indent=2))
hashes = {h for result in results for h in result['response_hashes']}
history = {h for result in results for h in result['history_evidence_hashes']}
if len(hashes) > 1 or len(history) > 1:
    raise SystemExit('PARITY MISMATCH: inspect response/history hashes; do not report these runs as equivalent.')
