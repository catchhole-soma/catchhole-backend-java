#!/usr/bin/env python3
"""Assemble a sanitized, checksummed local synthetic benchmark packet."""
import argparse
import hashlib
import json
import re
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--root', type=Path, required=True)
parser.add_argument('--destination', type=Path, required=True)
args = parser.parse_args()
root = args.root.resolve()
destination = args.destination.resolve()
destination.mkdir(parents=True, exist_ok=True)


def redact(value):
    return value.replace(str(root), '${MEASUREMENT_ROOT}').replace(str(Path.home()), '${USER_HOME}')


def copy(source, relative):
    target = destination / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(redact(source.read_text()))


for phase in ['before', 'after']:
    source = root / 'evidence' / phase
    for name in ['manifest.json', 'summary.json', 'accounting.json', 'pages.jsonl',
                 'api.jsonl', 'resources.jsonl', 'gc.log', 'fixture-readback.json']:
        copy(source / name, Path(phase) / name)
    for item in sorted((source / 'sql').iterdir()):
        if item.suffix in ['.sql', '.json']:
            copy(item, Path(phase) / 'sql' / item.name)
    copy(root / 'evidence' / f'{phase}-active' / 'active.json', Path(f'{phase}-active') / 'active.json')

for name in ['fixture.json', 'fixture-restored-after.json', 'verification-summary.json',
             'backend-full-tests-summary.json', 'ascii-verification-source.json', 'frontend-live-smoke.json']:
    copy(root / 'evidence' / name, Path(name))

(destination / '.gitignore').write_text('# Synthetic GC logs are intentionally reviewable.\n!before/gc.log\n!after/gc.log\n')
(destination / 'SANITIZATION.md').write_text(
    '# Evidence scope\n\n'
    'Local synthetic data only. Absolute measurement/home paths are replaced with placeholders. '
    'Private credentials, browser storage, cookie/token/header values, application logs, PostgreSQL logs '
    'and raw test traces are excluded. SQL files retain synthetic UUID bindings and EXPLAIN plans. '
    'Original artifacts and raw files remain outside the repository.\n\n'
    'Fixture hashes cover the named JSON/payload/metadata columns; they are not a checksum of every database value. '
    'The separate before/after readbacks preserve job statuses, run/latch metadata and target memberships. '
    'Initial navigation, quiescent polling and the active case are separate samples.\n')

credentials = json.loads((root / 'runtime' / 'credentials.json').read_text())
leaks = []
for item in sorted(destination.rglob('*')):
    if not item.is_file() or item.name == 'SHA256SUMS.json':
        continue
    contents = item.read_text()
    if (credentials['password'] in contents or str(root) in contents or str(Path.home()) in contents
            or re.search(r'Bearer\s+eyJ|"(?:accessToken|refreshToken|Authorization|Cookie)"\s*:', contents)):
        leaks.append(str(item.relative_to(destination)))
if leaks:
    raise RuntimeError('Credential/private-path-like evidence in: ' + ', '.join(leaks))

hashes = {str(item.relative_to(destination)): hashlib.sha256(item.read_bytes()).hexdigest()
          for item in sorted(destination.rglob('*')) if item.is_file() and item.name != 'SHA256SUMS.json'}
(destination / 'SHA256SUMS.json').write_text(json.dumps(hashes, indent=2) + '\n')
assert all(hashlib.sha256((destination / name).read_bytes()).hexdigest() == digest for name, digest in hashes.items())
print(json.dumps({'files': len(hashes), 'bytes': sum(item.stat().st_size for item in destination.rglob('*') if item.is_file()),
                  'credentialAndPrivatePathScan': 'passed', 'checksumVerification': 'passed'}))
