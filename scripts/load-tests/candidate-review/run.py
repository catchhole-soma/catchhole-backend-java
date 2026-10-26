#!/usr/bin/env python3
"""Bounded sequential HTTP comparison; one route per fresh JVM, stop on any error."""
import argparse
import hashlib
import http.client
import json
import math
import statistics
import time
import urllib.error
import urllib.request
from pathlib import Path


def route_paths(credentials, phase):
    prefix = f"/api/v1/works/{credentials['workId']}"
    batch = f"batchId={credentials['batchId']}"
    return {'character': prefix + '/setting-candidates?' + batch + '&reviewStatus=PENDING_REVIEW&page=0&size=20&includeLegacyCandidates=false',
            'world': prefix + '/world-setting-candidates?' + batch + '&reviewStatus=PENDING_REVIEW&page=0&size=20',
            'summary': prefix + '/world-setting-candidates' + ('?' + batch + '&page=0&size=1' if phase == 'before' else '/summary?' + batch)}


def request(credentials, path, token=None, body=None, timeout=45):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(credentials['api'] + path, headers=headers,
                                 data=json.dumps(body).encode() if body is not None else None)
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            raw = response.read()
            return response.status, raw, (time.perf_counter() - started) * 1000, None
    except urllib.error.HTTPError as error:
        return error.code, error.read(), (time.perf_counter() - started) * 1000, 'HTTPError'
    except (urllib.error.URLError, TimeoutError, ConnectionError, http.client.HTTPException) as error:
        return None, b'', (time.perf_counter() - started) * 1000, type(error).__name__


def login(credentials):
    status, raw, _, _ = request(credentials, '/api/v1/auth/login', body={
        'email': credentials['email'], 'password': credentials['password']})
    if status != 200:
        raise RuntimeError(f'Synthetic login failed with HTTP {status}')
    return json.loads(raw)['data']['accessToken']


def validate(data, route, phase):
    expected_total, expected_pending = (585, 123) if route == 'character' else (637, 62)
    assert data['totalCandidateCount'] == expected_total
    assert data['pendingCandidateCount'] == expected_pending
    assert data['reviewedCandidateCount'] == expected_total - expected_pending
    if route != 'summary':
        groups = data['groups']['content']
        assert len(groups) == (9 if route == 'character' else 11)
        candidates = [candidate for group in groups for candidate in group['candidates']]
        assert len(candidates) == expected_pending
        assert len({candidate['id'] for candidate in candidates}) == expected_pending
        assert all(candidate['reviewStatus'] == 'PENDING_REVIEW' for candidate in candidates)
        assert all(candidate['rawAiResultJson'] is None for candidate in candidates) if route == 'character' else True
    elif phase == 'after':
        assert 'groups' not in data
    return {'total': expected_total, 'pending': expected_pending, 'responseFields': sorted(data)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--credentials', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--phase', required=True, choices=['before', 'after'])
    parser.add_argument('--route', required=True, choices=['character', 'world', 'summary'])
    parser.add_argument('--warmups', type=int, default=3)
    parser.add_argument('--samples', type=int, default=30)
    parser.add_argument('--timeout', type=int, default=45)
    args = parser.parse_args()
    assert 0 <= args.warmups <= 3 and 1 <= args.samples <= 30 and 1 <= args.timeout <= 60
    credentials = json.loads(args.credentials.read_text())
    if credentials['api'] != 'http://127.0.0.1:29480':
        raise ValueError('Only the dedicated localhost API is allowed')
    path = route_paths(credentials, args.phase)[args.route]
    token = login(credentials)
    args.output.mkdir(parents=True, exist_ok=True)
    samples = []
    reference = None
    for index in range(args.warmups + args.samples):
        status, raw, ms, error = request(credentials, path, token=token, timeout=args.timeout)
        sample = {'index': index, 'kind': 'warmup' if index < args.warmups else 'measured',
                  'durationMs': ms, 'status': status, 'bodyBytes': len(raw), 'error': error}
        if status == 200:
            try:
                data = json.loads(raw)['data']
                sample['correctness'] = validate(data, args.route, args.phase)
                normalized = json.dumps(data, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()
                sample['responseSha256'] = hashlib.sha256(normalized).hexdigest()
                if reference is None:
                    reference = data
                    (args.output / 'response.json').write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
                assert reference == data, 'Read-only response changed within the series'
            except (AssertionError, KeyError, ValueError) as error:
                sample['error'] = 'ResponseValidation:' + str(error)
        samples.append(sample)
        (args.output / 'samples.json').write_text(json.dumps(samples, indent=2) + '\n')
        if sample['error'] or status != 200:
            break
    measured = [x['durationMs'] for x in samples if x['kind'] == 'measured' and x['status'] == 200 and not x['error']]
    failures = [x for x in samples if x['status'] != 200 or x['error']]
    summary = {'phase': args.phase, 'route': args.route, 'requestPath': path,
               'protocol': 'one fresh JVM per route; sequential; three warmups then thirty measured by default; stop on first error',
               'plannedWarmups': args.warmups, 'plannedMeasurements': args.samples,
               'completedWarmups': sum(x['kind'] == 'warmup' for x in samples),
               'completedMeasurements': len(measured), 'errors': len(failures), 'stoppedEarly': bool(failures),
               'medianMs': statistics.median(measured) if measured else None,
               'p95Ms': sorted(measured)[math.ceil(.95 * len(measured)) - 1] if measured else None,
               'responseSha256': next((x.get('responseSha256') for x in samples if x.get('responseSha256')), None)}
    (args.output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary))


if __name__ == '__main__':
    main()
