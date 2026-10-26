#!/usr/bin/env python3
"""Deterministic synthetic fixture, guarded to a dedicated localhost database."""
import argparse, base64, datetime as dt, hashlib, json, os, random, secrets, uuid
from pathlib import Path
from urllib.parse import urlsplit
import bcrypt, psycopg
from psycopg.types.json import Jsonb

NS = uuid.UUID('fc930470-7d41-4d25-b650-1a017b820da8')
TARGET_BYTES = 15_460_388
SEED = 20261024
NOW = dt.datetime(2026, 10, 23, 12)
def ident(name): return uuid.uuid5(NS, name)
def dump(value): return json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + '\n'
def main():
    p = argparse.ArgumentParser(); p.add_argument('--dsn', required=True); p.add_argument('--credentials', type=Path, required=True); p.add_argument('--manifest', type=Path, required=True); a=p.parse_args()
    parsed=urlsplit(a.dsn)
    if parsed.hostname not in ('127.0.0.1','localhost') or parsed.path != '/analysis_list_benchmark':
        raise ValueError('Only the dedicated localhost analysis_list_benchmark database is allowed')
    rng=random.Random(SEED)
    def random_text(n): return base64.b64encode(rng.randbytes((n+3)//4*3)).decode()[:n]
    credentials=json.loads(a.credentials.read_text()) if a.credentials.exists() else {'email':'analysis-benchmark@local.invalid','password':secrets.token_urlsafe(24)+'A1','api':'http://127.0.0.1:28080','frontend':'http://127.0.0.1:3300','workId':str(ident('work'))}
    a.credentials.parent.mkdir(parents=True,exist_ok=True); a.credentials.write_text(dump(credentials)); a.credentials.chmod(0o600)
    work=ident('work'); batches=[ident('batch-1'),ident('batch-2')]
    def insert(cur, table, values):
        columns=','.join(values); placeholders=','.join(['%s']*len(values))
        cur.execute(f'INSERT INTO {table} ({columns}) VALUES ({placeholders})',list(values.values()))
    with psycopg.connect(a.dsn) as c, c.cursor() as cur:
        cur.execute('SELECT count(*) FROM works WHERE id<>%s',(work,))
        if cur.fetchone()[0]: raise RuntimeError('Unexpected works in benchmark database; refusing reset')
        # This disposable database contains no production data. Resetting all seeded tables preserves Flyway history.
        cur.execute('TRUNCATE members CASCADE')
        insert(cur,'members',dict(id=1,email=credentials['email'],password_hash=bcrypt.hashpw(credentials['password'].encode(),bcrypt.gensalt()).decode(),phone_verified=False,email_verified=True,display_name='합성 측정 계정',status='ACTIVE',role='AUTHOR',created_at=NOW,updated_at=NOW))
        insert(cur,'works',dict(id=work,member_id=1,title='분석 목록 합성 벤치마크',genre='FANTASY',latest_episode_no=60,lifecycle_status='ACTIVE',created_at=NOW,updated_at=NOW))
        for b,batch in enumerate(batches):
            timestamp=NOW+dt.timedelta(hours=b)
            insert(cur,'upload_batches',dict(id=batch,work_id=work,member_id=1,upload_type='MULTI_EPISODE_SINGLE_FILE',source_type='TEXT_PASTE',status='COMPLETED',file_count=1,completed_at=timestamp,created_at=timestamp,updated_at=timestamp))
            fileid=ident(f'file-{b}')
            insert(cur,'upload_files',dict(id=fileid,batch_id=batch,file_role='EPISODE',original_filename=f'synthetic-{b+1}.txt',file_size=120000,parse_status='PARSED',detected_episode_start_no=b*30+1,detected_episode_end_no=b*30+30,detected_episode_count=30,created_at=timestamp,updated_at=timestamp))
            previous=None
            for seq in range(30):
                n=b*30+seq+1; episode=ident(f'episode-{n}'); job=ident(f'job-{n}'); digest=hashlib.sha256(f'episode-{n}'.encode()).hexdigest()
                status=('SUCCEEDED' if seq<18 else 'CANCELED') if b==0 else ('SUCCEEDED' if seq<2 else 'FAILED' if seq==2 else 'PENDING')
                journal='SEALED' if status=='SUCCEEDED' else 'INVALIDATED' if status=='CANCELED' else 'INCOMPLETE' if status=='FAILED' else 'PENDING'
                insert(cur,'episodes',dict(id=episode,work_id=work,source_file_id=fileid,episode_no=n,title=f'합성 {n}화',content_s3_key=f'synthetic/{n}.txt',content_hash=digest,char_count=4000,status='ANALYZED' if status=='SUCCEEDED' else 'UPLOADED',content_updated_at=timestamp,created_at=timestamp,updated_at=timestamp))
                automatic={'characters':[],'worldSettings':[],'syntheticPayload':random_text(180000)}
                state={'changes':[],'outputStateHash':digest,'syntheticPayload':random_text(200000)} if status in ('SUCCEEDED','FAILED') else None
                insert(cur,'analysis_jobs',dict(id=job,work_id=work,batch_id=batch,episode_id=episode,job_type='SETTING_EXTRACTION',status=status,current_step='합성 분석 상태',failure_code='UNEXPECTED_ERROR' if status=='FAILED' else None,analysis_mode='ORDERED_PROVISIONAL',review_mode='AUTOMATIC',analysis_run_id=ident(f'run-{b}'),run_generation=1,run_sequence=seq,predecessor_job_id=previous,run_base_state=Jsonb({'characters':[],'worldSettings':[],'syntheticPayload':random_text(100000)}) if seq==0 else None,input_state_hash=digest if state else None,source_content_hash=digest,source_content_s3_key=f'synthetic/{n}.txt',source_episode_no=n,state_journal=Jsonb(state) if state else None,journal_status=journal,automatic_input_state=Jsonb(automatic),automatic_applied_at=timestamp if status=='SUCCEEDED' else None,created_at=timestamp+dt.timedelta(seconds=seq),updated_at=timestamp+dt.timedelta(minutes=10,seconds=seq),completed_at=timestamp+dt.timedelta(minutes=10,seconds=seq) if status!='PENDING' else None))
                insert(cur,'analysis_job_episode_targets',dict(analysis_job_id=job,episode_id=episode)); previous=job
        cur.execute('SELECT sum(coalesce(octet_length(automatic_input_state::text),0)+coalesce(octet_length(run_base_state::text),0)+coalesce(octet_length(state_journal::text),0)) FROM analysis_jobs')
        delta=TARGET_BYTES-cur.fetchone()[0]
        if delta<0: raise AssertionError('Initial fixture exceeds target')
        cur.execute("UPDATE analysis_jobs SET automatic_input_state=jsonb_set(automatic_input_state,'{syntheticPayload}',to_jsonb((automatic_input_state->>'syntheticPayload') || %s::text)) WHERE id=%s",(random_text(delta),ident('job-60')))
        for b,(chars,worlds) in enumerate([(342,296),(56,113)]):
            jobs=18 if b==0 else 2
            for k in range(chars):
                n=b*30+k%jobs+1
                insert(cur,'setting_candidates',dict(id=ident(f'character-candidate-{b}-{k}'),work_id=work,episode_id=ident(f'episode-{n}'),analysis_job_id=ident(f'job-{n}'),entity_type='CHARACTER',entity_name=f'합성 인물 {k}',match_status='UNRESOLVED',candidate_kind='SETTING',attribute_name='성격',attribute_value=f'합성 값 {k}',value_type='STRING',review_status='PENDING_REVIEW',comparison_status='COMPLETED',suggested_operation='ADD',created_at=NOW,updated_at=NOW))
            for k in range(worlds):
                n=b*30+k%jobs+1
                insert(cur,'world_setting_candidates',dict(id=ident(f'world-candidate-{b}-{k}'),work_id=work,source_episode_id=ident(f'episode-{n}'),analysis_job_id=ident(f'job-{n}'),category='LOCATION',subject_name=f'합성 장소 {k}',setting_name='특징',extracted_value=f'합성 세계관 {k}',evidence_spans=Jsonb([]),review_status='PENDING_REVIEW',comparison_status='COMPLETED',suggested_operation='ADD',created_at=NOW,updated_at=NOW))
        cur.execute('ANALYZE')
        cur.execute('SELECT count(*),sum(coalesce(octet_length(automatic_input_state::text),0)+coalesce(octet_length(run_base_state::text),0)+coalesce(octet_length(state_journal::text),0)),md5(string_agg(id::text || automatic_input_state::text || coalesce(run_base_state::text,\'\') || coalesce(state_journal::text,\'\'),\'\' ORDER BY id)) FROM analysis_jobs')
        count,byte_count,data_hash=cur.fetchone(); assert (count,byte_count)==(60,TARGET_BYTES)
        cur.execute('SELECT batch_id,status,count(*) FROM analysis_jobs GROUP BY batch_id,status ORDER BY batch_id,status'); distribution=[list(map(str,r)) for r in cur.fetchall()]
        manifest={'version':2,'candidateEnumValues':{'entityType':'CHARACTER','matchStatus':'UNRESOLVED','valueType':'STRING','reviewStatus':'PENDING_REVIEW','comparisonStatus':'COMPLETED','suggestedOperation':'ADD'},'synthetic':True,'seed':SEED,'workId':str(work),'batchIds':list(map(str,batches)),'jobs':60,'episodes':60,'characterCandidates':398,'worldCandidates':409,'characterCandidatesByBatch':[342,56],'worldCandidatesByBatch':[296,113],'normalizedJsonUtf8Bytes':byte_count,'analysisJsonDataMd5':data_hash,'statusDistribution':distribution,'sourceScriptSha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),'fixedTimestamp':NOW.isoformat(),'jsonEncoding':'PostgreSQL JSONB ::text; fixed PRNG base64 payload; no production data','fixtureHashExcludes':'bcrypt salt, credentials, DB-generated auth session records'}
        manifest['fixtureSha256']=hashlib.sha256(dump(manifest).encode()).hexdigest(); a.manifest.parent.mkdir(parents=True,exist_ok=True); a.manifest.write_text(dump(manifest))
    print(dump({'seeded':True,'jobs':count,'normalizedJsonUtf8Bytes':byte_count,'fixtureSha256':manifest['fixtureSha256']}))
if __name__=='__main__': main()
