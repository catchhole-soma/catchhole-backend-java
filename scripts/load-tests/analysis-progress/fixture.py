#!/usr/bin/env python3
"""Deterministic, nested synthetic state for the dedicated progress benchmark DB."""
import argparse
import base64
import copy
import datetime as dt
import hashlib
import json
import random
import secrets
import uuid
from pathlib import Path
from urllib.parse import urlsplit

import bcrypt
import psycopg
from psycopg.types.json import Jsonb

NS = uuid.UUID('3826b684-7e39-491b-9382-b5409c29d7e0')
SEED = 20261024
TARGET_BYTES = 18_161_746
SUCCESS_BYTES = 17_301_914
LARGEST_BYTES = 1_803_551
NOW = dt.datetime(2026, 10, 24, 12)


def ident(name):
    return uuid.uuid5(NS, name)


def dump(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + '\n'


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--dsn', required=True)
    parser.add_argument('--credentials', type=Path, required=True)
    parser.add_argument('--manifest', type=Path, required=True)
    args = parser.parse_args()
    parsed = urlsplit(args.dsn)
    if parsed.hostname not in ('127.0.0.1', 'localhost') or parsed.port != 26432 or parsed.path != '/analysis_progress_benchmark':
        raise ValueError('Only the dedicated progress localhost:26432 database is allowed')
    rng = random.Random(SEED)

    def text(length):
        return base64.b64encode(rng.randbytes((length + 3) // 4 * 3)).decode()[:length]

    def insert(cur, table, values):
        cur.execute(f'INSERT INTO {table} ({",".join(values)}) VALUES ({",".join(["%s"] * len(values))})', list(values.values()))

    work = ident('work')
    batch = ident('batch')
    run = ident('run')
    jobs = [str(ident(f'job-{n}')) for n in range(1, 31)]
    credentials = json.loads(args.credentials.read_text()) if args.credentials.exists() else {
        'email': 'analysis-progress@local.invalid', 'password': secrets.token_urlsafe(24) + 'A1',
        'api': 'http://127.0.0.1:28480', 'frontend': 'http://127.0.0.1:3340',
        'workId': str(work), 'batchId': str(batch), 'jobIds': jobs,
    }
    args.credentials.parent.mkdir(parents=True, exist_ok=True)
    args.credentials.write_text(dump(credentials))
    args.credentials.chmod(0o600)
    # 21 increasing snapshots and a 1.72MiB final snapshot; exactly 16.50MiB completed total.
    success_sizes = [200_000 + n * 53_801 for n in range(21)] + [LARGEST_BYTES]
    success_sizes[20] += SUCCESS_BYTES - sum(success_sizes)
    row_sizes = success_sizes + [TARGET_BYTES - SUCCESS_BYTES] + [0] * 7
    structures = []
    with psycopg.connect(args.dsn) as connection, connection.cursor() as cur:
        cur.execute('SELECT count(*) FROM works WHERE id<>%s', (work,))
        if cur.fetchone()[0]:
            raise RuntimeError('Unexpected work; refusing reset')
        cur.execute('TRUNCATE members CASCADE')
        insert(cur, 'members', dict(id=1, email=credentials['email'], password_hash=bcrypt.hashpw(credentials['password'].encode(), bcrypt.gensalt()).decode(), phone_verified=False, email_verified=True, display_name='진행 조회 합성 계정', status='ACTIVE', role='AUTHOR', created_at=NOW, updated_at=NOW))
        insert(cur, 'works', dict(id=work, member_id=1, title='진행 조회 합성 벤치마크', genre='FANTASY', latest_episode_no=30, lifecycle_status='ACTIVE', created_at=NOW, updated_at=NOW))
        insert(cur, 'upload_batches', dict(id=batch, work_id=work, member_id=1, upload_type='MULTI_EPISODE_SINGLE_FILE', source_type='TEXT_PASTE', status='COMPLETED', file_count=1, completed_at=NOW, created_at=NOW, updated_at=NOW))
        file_id = ident('file')
        insert(cur, 'upload_files', dict(id=file_id, batch_id=batch, file_role='EPISODE', original_filename='synthetic-progress.txt', file_size=120000, parse_status='PARSED', detected_episode_start_no=1, detected_episode_end_no=30, detected_episode_count=30, created_at=NOW, updated_at=NOW))
        for n, target in enumerate(row_sizes, 1):
            episode = ident(f'episode-{n}')
            job = ident(f'job-{n}')
            source_hash = hashlib.sha256(f'synthetic-progress-episode-{n}'.encode()).hexdigest()
            status = 'SUCCEEDED' if n <= 22 else 'FAILED' if n == 23 else 'PENDING'
            journal_status = 'SEALED' if n <= 22 else 'INCOMPLETE' if n == 23 else 'PENDING'
            insert(cur, 'episodes', dict(id=episode, work_id=work, source_file_id=file_id, episode_no=n, title=f'합성 {n}화', content_s3_key=f'synthetic-progress/{n}.txt', content_hash=source_hash, char_count=4000, status='ANALYZED' if n <= 22 else 'FAILED' if n == 23 else 'UPLOADED', content_updated_at=NOW, created_at=NOW, updated_at=NOW))
            state = journal = base = None
            input_hash = None
            if target:
                # Many short nested facts/provenance/evidence objects, never a single giant blob.
                state = {'characters': {}, 'worldSettings': {}, 'references': {}}
                count = max(2, target // (6000 if n == 1 else 3000))
                for k in range(count):
                    candidate = str(ident(f'candidate-{n}-{k}'))
                    provenance = {'confirmationStatus': 'CONFIRMED', 'sourceEpisodeNo': max(1, n - 1), 'sourceCandidateIds': [candidate], 'reviewSource': 'AUTOMATIC'}
                    slots = {f'STATUS:synthetic-{slot}': {'factType': 'STATUS', 'factKey': f'합성 특성 {slot}', 'value': '합성 설정 ' + text(60), 'provenance': provenance, 'supportingSources': [{'availability': 'VERIFIED', 'sourceCandidateId': candidate, 'evidence': '합성 근거 ' + text(120)}]} for slot in range(4)}
                    state['characters'][f'character:{ident(f"character-{k}")}'] = {'name': f'합성 인물 {k}', 'aliases': [f'별칭 {k}'], 'slots': slots, 'absences': {}, 'historyFacts': [], 'snapshotVersion': n, 'identityEvidence': []}
                    if k % 4 == 0:
                        state['worldSettings'][f'world-setting:{ident(f"world-{k}")}'] = {'category': 'LOCATION', 'subjectName': f'합성 장소 {k}', 'settings': {'특징': {'value': '합성 세계관 ' + text(80), 'provenance': provenance}}, 'latestSourceEpisodeNo': max(1, n - 1)}
                input_hash = hashlib.sha256(canonical(state).encode()).hexdigest()
                journal = {'formatVersion': 1, 'runId': str(run), 'workId': str(work), 'generation': 1, 'sequence': n - 1, 'episodeNo': n, 'jobId': str(job), 'inputStateHash': input_hash, 'outputStateHash': input_hash if n <= 22 else None, 'changes': []}
                base = copy.deepcopy(state) if n == 1 else None
            insert(cur, 'analysis_jobs', dict(id=job, work_id=work, batch_id=batch, episode_id=episode, job_type='SETTING_EXTRACTION', status=status, current_step='합성 상태', failure_code='UNEXPECTED_ERROR' if n == 23 else None, analysis_mode='ORDERED_PROVISIONAL', review_mode='AUTOMATIC', analysis_run_id=run, run_generation=1, run_sequence=n-1, predecessor_job_id=ident(f'job-{n-1}') if n > 1 else None, run_base_state=Jsonb(base) if base else None, input_state_hash=input_hash, source_content_hash=source_hash, source_content_s3_key=f'synthetic-progress/{n}.txt', source_episode_no=n, state_journal=Jsonb(journal) if journal else None, journal_status=journal_status, automatic_input_state=Jsonb(state) if state else None, automatic_applied_at=NOW+dt.timedelta(minutes=n) if n <= 22 else None, created_at=NOW+dt.timedelta(seconds=n), updated_at=NOW+dt.timedelta(minutes=n), completed_at=NOW+dt.timedelta(minutes=n) if n <= 23 else None))
            insert(cur, 'analysis_job_episode_targets', dict(analysis_job_id=job, episode_id=episode))
            if target:
                cur.execute('SELECT coalesce(octet_length(automatic_input_state::text),0)+coalesce(octet_length(run_base_state::text),0)+coalesce(octet_length(state_journal::text),0) FROM analysis_jobs WHERE id=%s', (job,))
                actual = cur.fetchone()[0]
                delta = target - actual
                if delta < 0:
                    raise AssertionError(f'Nested fixture row {n} exceeds target: {actual}>{target}')
                # Small distributed synthetic evidence extension gives exact JSONB::text byte parity.
                sources = [slot['supportingSources'][0] for character in state['characters'].values() for slot in character['slots'].values()]
                for index, evidence in enumerate(sources):
                    evidence['evidence'] += text(delta // len(sources) + (index < delta % len(sources)))
                input_hash = hashlib.sha256(canonical(state).encode()).hexdigest()
                journal.update(inputStateHash=input_hash, outputStateHash=input_hash if n <= 22 else None)
                cur.execute('UPDATE analysis_jobs SET automatic_input_state=%s,input_state_hash=%s,state_journal=%s WHERE id=%s', (Jsonb(state), input_hash, Jsonb(journal), job))
                cur.execute('SELECT coalesce(octet_length(automatic_input_state::text),0)+coalesce(octet_length(run_base_state::text),0)+coalesce(octet_length(state_journal::text),0) FROM analysis_jobs WHERE id=%s', (job,))
                assert cur.fetchone()[0] == target
                structures.append({'episodeNo': n, 'characters': len(state['characters']), 'worldSettings': len(state['worldSettings']), 'jsonBytes': target, 'evidenceExtensionBytes': delta})
        for k in range(410):
            n = k % 22 + 1
            insert(cur, 'setting_candidates', dict(id=ident(f'character-candidate-{k}'), work_id=work, episode_id=ident(f'episode-{n}'), analysis_job_id=ident(f'job-{n}'), entity_type='CHARACTER', entity_name=f'합성 인물 {k}', match_status='UNRESOLVED', candidate_kind='SETTING', attribute_name='성격', attribute_value=f'합성 성격 {k}', value_type='STRING', review_status='PENDING_REVIEW', comparison_status='COMPLETED', suggested_operation='ADD', created_at=NOW+dt.timedelta(seconds=k), updated_at=NOW))
        for k in range(404):
            n = k % 22 + 1
            insert(cur, 'world_setting_candidates', dict(id=ident(f'world-candidate-{k}'), work_id=work, source_episode_id=ident(f'episode-{n}'), analysis_job_id=ident(f'job-{n}'), category='LOCATION', subject_name=f'합성 장소 {k}', setting_name='특징', extracted_value='합성 세계관 ' + text(100), evidence_spans=Jsonb([{'start': 0, 'end': 40, 'text': '합성 원문 근거 ' + text(60)}]), raw_extraction_json=Jsonb({'subjectName': f'합성 장소 {k}', 'settingName': '특징', 'value': '합성 추출값 ' + text(80)}), review_status='PENDING_REVIEW', comparison_status='COMPLETED', suggested_operation='ADD', created_at=NOW+dt.timedelta(seconds=k), updated_at=NOW))
        cur.execute('ANALYZE')
        cur.execute("SELECT count(*),sum(coalesce(octet_length(automatic_input_state::text),0)+coalesce(octet_length(run_base_state::text),0)+coalesce(octet_length(state_journal::text),0)),md5(string_agg(id::text || coalesce(automatic_input_state::text,'') || coalesce(run_base_state::text,'') || coalesce(state_journal::text,''),'' ORDER BY id)) FROM analysis_jobs")
        count, size, data_hash = cur.fetchone()
        assert (count, size) == (30, TARGET_BYTES)
        cur.execute('SELECT status,journal_status,count(*),sum(coalesce(octet_length(automatic_input_state::text),0)+coalesce(octet_length(run_base_state::text),0)+coalesce(octet_length(state_journal::text),0)),count(automatic_applied_at) FROM analysis_jobs GROUP BY status,journal_status ORDER BY status')
        distribution = [list(row) for row in cur.fetchall()]
        cur.execute("SELECT md5(string_agg(id::text || extracted_value || evidence_spans::text || raw_extraction_json::text,'' ORDER BY id)) FROM world_setting_candidates")
        candidate_hash = cur.fetchone()[0]
        manifest = {'version': 1, 'synthetic': True, 'seed': SEED, 'workId': str(work), 'batchId': str(batch), 'runId': str(run), 'jobIds': jobs, 'jobs': count, 'episodes': 30, 'characterCandidates': 410, 'worldCandidates': 404, 'worldCandidateDataMd5': candidate_hash, 'normalizedJsonUtf8Bytes': size, 'analysisJsonDataMd5': data_hash, 'statusDistribution': distribution, 'nestedStructures': structures, 'sourceScriptSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), 'fixedTimestamp': NOW.isoformat(), 'jsonEncoding': 'PostgreSQL JSONB::text UTF-8; nested synthetic characters/world settings with fixed-PRNG short evidence; no production data', 'fixtureHashExcludes': 'bcrypt salt, private credentials and auth records'}
        manifest['fixtureSha256'] = hashlib.sha256(dump(manifest).encode()).hexdigest()
        args.manifest.parent.mkdir(parents=True, exist_ok=True)
        args.manifest.write_text(dump(manifest))
    print(dump({'seeded': True, 'jobs': count, 'normalizedJsonUtf8Bytes': size, 'fixtureSha256': manifest['fixtureSha256']}))


if __name__ == '__main__':
    main()
