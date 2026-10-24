#!/usr/bin/env python3
"""Create private localhost-only credentials; never print credential values."""
import argparse
import json
import secrets
from pathlib import Path
from urllib.parse import quote

from fixture import dump, ident

p = argparse.ArgumentParser()
p.add_argument('--root', type=Path, required=True)
a = p.parse_args()
runtime = a.root / 'runtime'
runtime.mkdir(parents=True, exist_ok=True)
path = runtime / 'credentials.json'
if not path.exists():
    password = secrets.token_urlsafe(24)
    data = {'email': 'candidate-review@local.invalid', 'password': secrets.token_urlsafe(24) + 'A1',
            'api': 'http://127.0.0.1:29480', 'frontend': 'http://127.0.0.1:3440',
            'workId': str(ident('work')), 'batchId': str(ident('batch')),
            'jobIds': [str(ident(f'job-{n}')) for n in range(1, 31)],
            'dsn': f'postgresql://candidate_review_bench:{quote(password)}@127.0.0.1:27432/candidate_review_benchmark'}
    path.write_text(dump(data))
    (runtime / 'postgres.env').write_text('POSTGRES_DB=candidate_review_benchmark\nPOSTGRES_USER=candidate_review_bench\nPOSTGRES_PASSWORD=' + password + '\n')
    (runtime / 'application-benchmark.properties').write_text('spring.datasource.url=jdbc:postgresql://127.0.0.1:27432/candidate_review_benchmark\nspring.datasource.username=candidate_review_bench\nspring.datasource.password=' + password + '\n')
for name in ['credentials.json', 'postgres.env', 'application-benchmark.properties']:
    (runtime / name).chmod(0o600)
print(json.dumps({'runtimePrepared': str(runtime), 'credentialMode': '0600'}))
