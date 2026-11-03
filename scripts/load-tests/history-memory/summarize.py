#!/usr/bin/env python3
"""Summarize isolated GH374 measurements. Never turn failed/missing requests into zeroes."""
import argparse
import json
from pathlib import Path
import statistics

parser = argparse.ArgumentParser()
parser.add_argument('baseline', type=Path)
parser.add_argument('candidate', type=Path)
parser.add_argument('--output', type=Path)
args = parser.parse_args()


def load(folder):
    exit_code = (folder / 'exit-code.txt').read_text().strip()
    if exit_code != '0':
        raise SystemExit(f'{folder}: exit {exit_code}; keep artifacts and do not summarize as successful samples')
    fixtures = sorted(folder.glob('sample-*-fixture.json'))
    parities = sorted(folder.glob('sample-*-parity.json'))
    environments = sorted(folder.glob('sample-*-environment.json'))
    if not fixtures or len(fixtures) != len(parities) or len(fixtures) != len(environments):
        raise SystemExit(f'{folder}: incomplete parity records')
    phases = {}
    evidence = []
    selected_phases = ('overlap',) if list(folder.glob('sample-*-overlap.json')) else ('episodes', 'claim')
    for phase in selected_phases:
        files = sorted(folder.glob(f'sample-*-{phase}.json'))
        if len(files) != len(fixtures):
            raise SystemExit(f'{folder}: missing {phase} samples')
        rows = [json.loads(path.read_text()) for path in files]
        if any(row['httpStatus'] != 200 for row in rows):
            raise SystemExit(f'{folder}: non-successful {phase} response')
        if any(query['failure'] for row in rows for query in row['queries']):
            raise SystemExit(f'{folder}: JDBC failure')
        metrics = {}
        for key in rows[0]['runtime']:
            values = [row['runtime'][key] for row in rows]
            metrics[key] = {'median': statistics.median(values), 'min': min(values), 'max': max(values)}
        phases[phase] = {'samples': len(rows), 'runtime': metrics,
                         'jobJsonRows': [row['rowsSelectingJobJson'] for row in rows],
                         'selectQueries': [len(row['queries']) for row in rows],
                         'selectConsumedRows': [sum(query['consumedRows'] for query in row['queries']) for row in rows],
                         'responseBytes': [row['responseBytes'] for row in rows]}
        for path, row in zip(files, rows):
            evidence.append({'sample': path.stem, **row})
    fixture_values = [json.loads(path.read_text()) for path in fixtures]
    parity_values = [json.loads(path.read_text()) for path in parities]
    for fixture in fixture_values:
        if fixture['heapMaxBytes'] != 768 * 1024 * 1024:
            raise SystemExit(f'{folder}: unexpected maximum heap')
        totals = fixture['historicalJsonTotals'][0]
        if totals['jobs'] != 41 or sum(totals[key] for key in ('input_bytes','base_bytes','journal_bytes')) != 75_974_920:
            raise SystemExit(f'{folder}: unexpected historical JSON scale')
        if (totals['v1_jobs'], totals['v2_jobs'], totals['v1_bytes'], totals['v2_bytes']) != (35, 6, 29_538_310, 810_061):
            raise SystemExit(f'{folder}: unexpected journal version/byte distribution')
    if any(fixture['expectedInputSemanticHash'] != parity['inputSemanticHash'] for fixture, parity in zip(fixture_values, parity_values)):
        raise SystemExit(f'{folder}: initial capture and claimed input differ')
    return {'commit': (folder / 'commit.txt').read_text().strip(), 'phases': phases,
            'sourceRevision': json.loads((folder / 'source-revision.json').read_text()),
            'fixtures': fixture_values, 'parity': parity_values, 'evidence': evidence,
            'environment': [json.loads(path.read_text()) for path in environments],
            'java': (folder / 'java-version.txt').read_text(),
            'docker': (folder / 'docker-environment.txt').read_text().strip(),
            'harness': [line.split()[0] for line in (folder / 'harness.sha256').read_text().splitlines()]}

before, after = load(args.baseline), load(args.candidate)
if before['phases'].keys() != after['phases'].keys():
    raise SystemExit('Before/after request scenario differs')
if before['harness'] != after['harness']:
    raise SystemExit('Before/after measurement harness differs')
if len(before['parity']) != len(after['parity']):
    raise SystemExit('Before/after successful sample count differs')
replay_pairs = {tuple(f['historicalV1V2ReplayHashes']) for side in (before, after) for f in side['fixtures']}
if len(replay_pairs) != 1:
    raise SystemExit('Historical v1/v2 replay mismatch')
keys = ('inputSemanticHash','claimSemanticHash','episodeListSemanticHash')
for key in keys:
    values = {row[key] for side in (before, after) for row in side['parity']}
    if len(values) != 1:
        raise SystemExit(f'Semantic mismatch: {key}')
for side in (before, after):
    if not all(row['verifiedHistoryFacts'] == 641 and row['pendingReferences'] == 3
               and row['currentWorldSubjects'] == 181 and row['retainedRowsUnchanged'] for row in side['parity']):
        raise SystemExit('Incomplete content/evidence preservation')
query_profiles = {}
profile_ids = {}
def compact_queries(side):
    for record in side['evidence']:
        totals = {}
        queries = record.pop('queries')
        for query in queries:
            key = (query['sql'], tuple(query['selectedColumns']))
            if key not in profile_ids:
                ident = f'q{len(profile_ids) + 1:03d}'
                profile_ids[key] = ident
                query_profiles[ident] = {'sql': query['sql'], 'selectedColumns': query['selectedColumns'],
                    'selectsJobJson': any(column in query['selectedColumns'] for column in ('automatic_input_state','run_base_state','state_journal'))}
            ident = profile_ids[key]
            total = totals.setdefault(ident, {'profileId': ident, 'executions': 0, 'consumedRows': 0, 'executeMillis': 0.0, 'failures': []})
            total['executions'] += 1
            total['consumedRows'] += query['consumedRows']
            total['executeMillis'] += query['executeMillis']
            if query['failure']:
                total['failures'].append(query['failure'])
        if sum(row['consumedRows'] for ident, row in totals.items() if query_profiles[ident]['selectsJobJson']) != record['rowsSelectingJobJson']:
            raise SystemExit('Compacted JSON-row evidence differs from original query records')
        record['queryTotals'] = list(totals.values())
    for fixture in side['fixtures']:
        arguments = []
        for argument in fixture['jvmArguments']:
            argument = argument.replace(str(args.baseline.resolve()), '<artifact>').replace(str(args.candidate.resolve()), '<artifact>')
            if argument.startswith('-Dorg.gradle.internal.worker.tmpdir='):
                argument = '-Dorg.gradle.internal.worker.tmpdir=<checkout>/build/tmp/test/work'
            arguments.append(argument.replace(str(Path.home()), '<home>'))
        fixture['jvmArguments'] = arguments
compact_queries(before)
compact_queries(after)
result = {'schemaVersion': 1, 'scenario': 'two-overlapping-client-requests' if 'overlap' in before['phases'] else 'sequential-one-request-at-a-time', 'baseline': before, 'candidate': after,
          'limitations': ['Synthetic content and scale; not an exact copy of production distribution, network or concurrent traffic.',
                          'Sampled heap every 5ms is not RSS or an absolute peak. Allocation includes the whole test JVM.',
                          'No external LLM or S3, no production writes. Initial claim success does not mean an episode completed.',
                          'Historical JSON text bytes do not equal network bytes or Java heap.',
                          'Identity-normalized semantic hashes omit leases/timestamps and re-sort only UUID-ordered owner/reference sets, with actual reference order asserted per fixture.',
                          'Stored input hashes are verified within each fixture; the identity-dependent value is omitted only from cross-fixture response comparison.'],
          'allSemanticHashesEqual': True, 'queryProfiles': query_profiles}
if 'overlap' in before['phases']:
    result['limitations'].append('The latest episode raw status may race between UPLOADED/CHUNKING during overlap and is normalized only for cross-run hashes; analysisStatus must remain IN_PROGRESS.')
    result['limitations'].append('Both requests start together, but faster completion changes actual overlap duration; this is not a constant-duration concurrency load test.')
text = json.dumps(result, ensure_ascii=False, indent=2) + '\n'
if args.output:
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(text)
print(json.dumps({'allSemanticHashesEqual': True, 'baseline': before['phases'], 'candidate': after['phases']}, ensure_ascii=False, indent=2))
