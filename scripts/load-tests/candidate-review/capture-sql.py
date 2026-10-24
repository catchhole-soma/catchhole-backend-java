#!/usr/bin/env python3
"""Capture actual API SELECTs and binds on the owned DB; never run during timings."""
import argparse
import datetime
import json
import re
import subprocess
import time
from pathlib import Path
from urllib.parse import urlsplit

import psycopg

from run import login, request, route_paths

p = argparse.ArgumentParser()
p.add_argument('--credentials', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
p.add_argument('--phase', choices=['before', 'after'], required=True)
p.add_argument('--route', choices=['character', 'world', 'summary'], required=True)
a = p.parse_args()
credentials = json.loads(a.credentials.read_text())
parsed = urlsplit(credentials['dsn'])
if credentials['api'] != 'http://127.0.0.1:29480' or parsed.hostname != '127.0.0.1' or parsed.port != 27432 or parsed.path != '/candidate_review_benchmark':
    raise ValueError('Dedicated localhost runtime only')
token = login(credentials)
a.output.mkdir(parents=True, exist_ok=True)
index = []
with psycopg.connect(credentials['dsn'], autocommit=True) as connection, connection.cursor() as cur:
    cur.execute('SHOW log_min_duration_statement')
    original = cur.fetchone()[0]
    cur.execute('ALTER SYSTEM SET log_min_duration_statement = 0')
    cur.execute('SELECT pg_reload_conf()')
    time.sleep(.2)
    try:
        since = datetime.datetime.now(datetime.timezone.utc).isoformat()
        status, _, elapsed, error = request(credentials, route_paths(credentials, a.phase)[a.route], token=token, timeout=60)
        time.sleep(.2)
        raw = subprocess.check_output(['docker', 'logs', '--since', since, 'candidate-review-benchmark-20261025-postgres'], stderr=subprocess.STDOUT, text=True)
        (a.output / 'postgres.log').write_text(raw)
        entries = re.split(r'(?m)^(?=\d{4}-\d\d-\d\d \d\d:\d\d:\d\d)', raw)
        queries = []
        expect_parameters = False
        for entry in entries:
            match = re.search(r'duration: [\d.]+ ms\s+(?:execute [^:]+|statement):\s*((?:select|with)\b.*)', entry, re.S | re.I)
            if match:
                queries.append([match.group(1).strip(), {}])
                expect_parameters = True
            elif 'DETAIL:  parameters:' not in entry:
                expect_parameters = False
            if 'DETAIL:  parameters:' in entry and queries and expect_parameters:
                paramtext = entry.split('DETAIL:  parameters:', 1)[1].strip()
                expect_parameters = False
                queries[-1][1] = {int(n): value for n, value in re.findall(r"\$(\d+) = ('(?:[^']|'')*'|NULL)(?:, |$)", paramtext)}
        for sql, parameters in queries:
            sql = re.split(r'\n\d{4}-\d\d-\d\d ', sql)[0].split('\nDETAIL:')[0].strip()
            bound = re.sub(r'\$(\d+)', lambda match: parameters.get(int(match.group(1)), match.group(0)), sql)
            number = len(index)
            filename = f'{number:02d}.sql'
            (a.output / filename).write_text(bound + ';\n')
            select = re.split(r'\bfrom\b', sql, maxsplit=1, flags=re.I)[0]
            item = {'number': number, 'sqlFile': filename, 'selectedExpressions': select,
                    'includesAnalysisJson': any(name in select for name in ['automatic_input_state', 'run_base_state', 'state_journal']),
                    'includesRawAi': any(name in select for name in ['raw_ai_result_json', 'raw_extraction_json']),
                    'parametersBound': not bool(re.search(r'\$\d+', bound))}
            if item['parametersBound']:
                try:
                    cur.execute('EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) ' + bound)
                    plan = cur.fetchone()[0]
                    (a.output / f'{number:02d}-explain.json').write_text(json.dumps(plan, indent=2) + '\n')
                    item.update(planningMs=plan[0]['Planning Time'], executionMs=plan[0]['Execution Time'], returnedRows=plan[0]['Plan']['Actual Rows'])
                except Exception as plan_error:
                    item['explainError'] = str(plan_error)
            index.append(item)
    finally:
        # This is the task-owned DB; restore the previous effective setting even on API error.
        cur.execute("ALTER SYSTEM SET log_min_duration_statement = '" + original.replace("'", "''") + "'")
        cur.execute('SELECT pg_reload_conf()')
(a.output / 'index.json').write_text(json.dumps(index, indent=2) + '\n')
summary = {'phase': a.phase, 'route': a.route, 'httpStatus': status, 'requestError': error,
           'diagnosticDurationMs': elapsed, 'timingSample': False, 'capturedSelects': len(index),
           'jsonSelectingQueries': sum(x['includesAnalysisJson'] for x in index),
           'explainFailures': sum('explainError' in x or not x['parametersBound'] for x in index)}
(a.output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary))
