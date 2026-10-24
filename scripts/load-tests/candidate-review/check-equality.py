#!/usr/bin/env python3
"""Untimed HTTP DTO oracle, including successful old summary on the small-JSON fixture."""
import argparse
import json
from pathlib import Path

from run import login, request, route_paths

p = argparse.ArgumentParser()
p.add_argument('--root', type=Path, required=True)
p.add_argument('--phase', choices=['before', 'after'], required=True)
a = p.parse_args()
credentials = json.loads((a.root / 'runtime/credentials.json').read_text())
token = login(credentials)
results = []
for route, path in route_paths(credentials, a.phase).items():
    status, raw, _, error = request(credentials, path, token=token)
    if status != 200 or error:
        raise RuntimeError(f'{route} correctness HTTP {status}: {error}')
    data = json.loads(raw)['data']
    dest = a.root / 'evidence' / a.phase / ('correctness-' + route)
    dest.mkdir(parents=True, exist_ok=True)
    (dest / 'response.json').write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
    if a.phase == 'before':
        reference = a.root / 'evidence/before' / ('world' if route == 'summary' else route) / 'response.json'
    else:
        reference = a.root / 'evidence/before' / ('correctness-' + route) / 'response.json'
    expected = json.loads(reference.read_text()) if reference.exists() else None
    if route == 'summary' and expected is not None:
        expected.pop('groups', None)
    comparable = {key: value for key, value in data.items() if key != 'groups'} if route == 'summary' else data
    equal = expected == comparable if expected is not None else None
    results.append({'route': route, 'httpStatus': status, 'reference': str(reference), 'fullDataEqual': equal,
                    'scope': 'all data fields, excluding groups for summary; no CommonResponse envelope timestamp',
                    'topLevelDifferences': [key for key in set(expected or {}) | set(comparable) if (expected or {}).get(key) != comparable.get(key)] if expected is not None else []})
out = a.root / 'evidence' / a.phase / 'correctness-equality.json'
out.write_text(json.dumps({'phase': a.phase, 'timingSample': False, 'fixture': 'small-json browser correctness only', 'results': results}, indent=2) + '\n')
print(json.dumps({'phase': a.phase, 'results': results}))
assert all(row['fullDataEqual'] is not False for row in results)
