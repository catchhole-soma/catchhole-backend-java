#!/usr/bin/env python3
"""Analyze preserved progress benchmark timings; never sends application requests."""
import argparse
import json
import math
import re
from collections import defaultdict
from pathlib import Path
from urllib.parse import urlsplit

parser = argparse.ArgumentParser()
parser.add_argument('--phase', type=Path, required=True)
args = parser.parse_args()
phase = args.phase


def rows(name):
    path = phase / name
    return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []


def stats(values):
    values = sorted(value for value in values if value is not None and math.isfinite(value))
    count = len(values)
    return {'n': count, 'median': (values[(count-1)//2] + values[count//2]) / 2 if count else None,
            'p95': values[math.ceil(count*.95)-1] if count else None, 'max': values[-1] if count else None}


def label(request):
    return request['method'] + ' ' + re.sub(r'[0-9a-f]{8}-[0-9a-f-]{27,}', ':id', urlsplit(request['url']).path)


def api_requests(page):
    return [request for request in page['requests'] if request['url'].startswith('http://127.0.0.1:28480/api/')]


pages = rows('pages.jsonl')
navigations = [page for page in pages if page['kind'] == 'navigation']
route_samples = defaultdict(list)
latest_completion = []
for page in navigations:
    requests = [request for request in api_requests(page) if request['startMs'] <= page['observationCutoffMs']]
    latest_completion.append(max((request['endMs'] or 0 for request in requests), default=0))
    for route in sorted({label(request) for request in requests}):
        selected = [request for request in requests if label(request) == route]
        route_samples[route].append({'count': len(selected), 'encodedResponseBodyBytes': sum(request.get('sizes', {}).get('responseBodySize', 0) for request in selected),
                                    'responseHeadersBytes': sum(request.get('sizes', {}).get('responseHeadersSize', 0) for request in selected)})
polling = []
for page in [page for page in pages if page['kind'] == 'polling']:
    steady = [request for request in api_requests(page) if page['observationStartMs'] <= request['startMs'] <= page['observationCutoffMs']]
    by_route = {}
    for route in sorted({label(request) for request in steady}):
        selected = [request for request in steady if label(request) == route]
        by_route[route] = {'count': len(selected), 'encodedResponseBodyBytes': sum(request.get('sizes', {}).get('responseBodySize', 0) for request in selected),
                           'failed': sum(request.get('failed', False) or request.get('status', 0) >= 400 for request in selected), 'censored': sum(request.get('censored', False) for request in selected)}
    polling.append({'index': page['index'], 'success': page['success'], 'observationStartMs': page['observationStartMs'],
                    'observationCutoffMs': page['observationCutoffMs'], 'windowMs': page['observationCutoffMs']-page['observationStartMs'], 'routes': by_route})
resources = rows('resources.jsonl')
end_epoch = (phase / 'summary.json').stat().st_mtime
window = [row for row in resources if row['epochSeconds'] <= end_epoch and not row.get('processUnavailable')]


def resource_stats(samples):
    result = {'samples': len(samples)}
    if samples:
        result.update(startEpochSeconds=samples[0]['epochSeconds'], endEpochSeconds=samples[-1]['epochSeconds'],
                      maxRssMiB=max(row['rssKiB']/1024 for row in samples),
                      maxHeapUsedMiB=max(sum(row['jstatGc'].get(key, 0) or 0 for key in ['S0U', 'S1U', 'EU', 'OU'])/1024 for row in samples),
                      gcDeltas={key: samples[-1]['jstatGc'][key]-samples[0]['jstatGc'][key] for key in ['YGC', 'YGCT', 'FGC', 'FGCT', 'CGC', 'CGCT', 'GCT']})
    return result


resource_result = {**resource_stats(window), 'rawSamples': len(resources), 'runnerSummaryEpochSeconds': end_epoch}
steady_resources = []
for page in [page for page in pages if page['kind'] == 'polling']:
    begin = (page['timeOriginEpochMs'] + page['observationStartMs']) / 1000
    end = (page['timeOriginEpochMs'] + page['observationCutoffMs']) / 1000
    selected = [row for row in window if begin <= row['epochSeconds'] <= end]
    steady_resources.append({'index': page['index'], 'boundStartEpochSeconds': begin, 'boundEndEpochSeconds': end, **resource_stats(selected)})
result = {'navigationSamples': len(navigations), 'latestObservedInitialApiCompletionMs': stats(latest_completion),
          'initialApiRoutes': {route: {key: stats([sample[key] for sample in values]) for key in ['count', 'encodedResponseBodyBytes', 'responseHeadersBytes']} for route, values in route_samples.items()},
          'initialApiTotalBodyBytes': stats([sum(request.get('sizes', {}).get('responseBodySize', 0) for request in api_requests(page) if request['startMs'] <= page['observationCutoffMs']) for page in navigations]),
          'pollingSteadyWindows': polling, 'resources': resource_result, 'matchedSteadyWindowResources': steady_resources,
          'failures': {'pages': sum(not page['success'] for page in pages), 'collectorErrors': sum(len(page.get('collectorErrors', [])) for page in pages),
                       'censored': sum(request.get('censored', False) for page in pages for request in page['requests'])},
          'notes': ['Ready asserts 30 progress rows/statuses and resume button; comparison count completion is not a ready dependency.',
                    'Initial API completion is latest observed relevant response, separate from ready/load.',
                    'Steady counts include starts inside the exact observation bounds; initial navigation requests are separate.',
                    'Encoded bodies exclude response headers; CDP OPTIONS remain separate in raw pages.',
                    'Heap/RSS peaks are 0.5s observations; jstat GC time is collector time, not HTTP latency or all wall-clock pause time.']}
(phase / 'accounting.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n')
print(json.dumps(result, ensure_ascii=False))
