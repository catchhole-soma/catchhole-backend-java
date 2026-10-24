#!/usr/bin/env python3
"""Read all relevant synthetic rows; optional smaller JSON for untimed browser correctness."""
import argparse
import hashlib
import json
from pathlib import Path
from urllib.parse import urlsplit

import psycopg

p = argparse.ArgumentParser()
p.add_argument('--credentials', required=True, type=Path)
p.add_argument('--output', required=True, type=Path)
p.add_argument('--small-json', action='store_true')
a = p.parse_args()
credentials = json.loads(a.credentials.read_text())
parsed = urlsplit(credentials['dsn'])
if parsed.hostname != '127.0.0.1' or parsed.port != 27432 or parsed.path != '/candidate_review_benchmark':
    raise ValueError('Dedicated localhost DB only')
tables = ['works', 'upload_batches', 'upload_files', 'episodes', 'analysis_jobs', 'analysis_job_episode_targets', 'setting_candidates', 'world_setting_candidates', 'character_fact_comparison_batches']
with psycopg.connect(credentials['dsn']) as connection, connection.cursor() as cur:
    if a.small_json:
        cur.execute("UPDATE analysis_jobs SET automatic_input_state=jsonb_build_object('characters','{}'::jsonb,'worldSettings','{}'::jsonb), run_base_state=CASE WHEN run_base_state IS NULL THEN NULL ELSE jsonb_build_object('characters','{}'::jsonb,'worldSettings','{}'::jsonb) END")
    rows = {}
    for table in tables:
        cur.execute(f"SELECT count(*),md5(string_agg(to_jsonb(t)::text,'' ORDER BY to_jsonb(t)::text)) FROM {table} t")
        count, checksum = cur.fetchone()
        rows[table] = {'rows': count, 'allColumnDataMd5': checksum}
    cur.execute('SELECT sum(coalesce(octet_length(automatic_input_state::text),0)+coalesce(octet_length(run_base_state::text),0)+coalesce(octet_length(state_journal::text),0)) FROM analysis_jobs')
    size = cur.fetchone()[0]
state = {'synthetic': True, 'scenario': 'small-json browser correctness only' if a.small_json else 'incident-size API fixture',
         'analysisJsonBytes': size, 'tables': rows,
         'excludes': 'member bcrypt salt, private credentials and auth/session rows; every column included in the listed tables'}
state['stateSha256'] = hashlib.sha256(json.dumps(rows, sort_keys=True).encode()).hexdigest()
a.output.parent.mkdir(parents=True, exist_ok=True)
a.output.write_text(json.dumps(state, ensure_ascii=False, indent=2) + '\n')
print(json.dumps({'saved': str(a.output), 'stateSha256': state['stateSha256'], 'analysisJsonBytes': size}))
