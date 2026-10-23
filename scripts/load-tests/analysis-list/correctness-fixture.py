#!/usr/bin/env python3
"""Temporary real-API book smoke fixtures in the disposable benchmark database only."""
import argparse,datetime as dt,hashlib,json,shutil,tempfile,uuid
from pathlib import Path
from urllib.parse import urlsplit
import psycopg
from psycopg.types.json import Jsonb
p=argparse.ArgumentParser();p.add_argument('action',choices=['create','cleanup']);p.add_argument('--dsn',required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
u=urlsplit(a.dsn)
if u.hostname!='127.0.0.1' or u.port!=25432 or u.path!='/analysis_list_benchmark':raise ValueError('Dedicated disposable localhost DB only')
NS=uuid.UUID('fc930470-7d41-4d25-b650-1a017b820da8')
def ident(s):return uuid.uuid5(NS,s)
performance_work=ident('work');cases=['book-only','mixed'];allowed={performance_work,*[ident('correctness-'+x)for x in cases]}
root=Path(tempfile.gettempdir())/'catchhole-e2e-storage';files=root/'analysis-list-correctness'
now=dt.datetime(2026,10,24,12)
with psycopg.connect(a.dsn) as c,c.cursor() as cur:
    cur.execute('select id from works');found={r[0]for r in cur.fetchall()}
    if not found<=allowed:raise RuntimeError('Unexpected works; refusing synthetic fixture mutation')
    if a.action=='cleanup':
        cur.execute("select count(*) from members where email<>'analysis-benchmark@local.invalid'")
        if cur.fetchone()[0]:raise RuntimeError('Unexpected member; refusing reset')
        cur.execute('TRUNCATE members CASCADE');c.commit();shutil.rmtree(files,ignore_errors=True)
        print(json.dumps({'clearedSyntheticOnly':True,'restorePerformanceFixtureRequired':True}));raise SystemExit()
    if found!= {performance_work}:raise RuntimeError('Create once against the canonical performance seed')
    result={'api':'http://127.0.0.1:28080','storageRoot':str(root),'works':{}}
    def insert(table,values):cur.execute(f"insert into {table} ({','.join(values)}) values ({','.join(['%s']*len(values))})",list(values.values()))
    for case in cases:
        work=ident('correctness-'+case);batch=ident('correctness-batch-'+case);book=ident('correctness-book-'+case);revision=ident('correctness-revision-'+case);job=ident('correctness-job-'+case)
        insert('works',dict(id=work,member_id=1,title='실제 API '+case+' 검증',genre='FANTASY',latest_episode_no=1 if case=='mixed' else 0,lifecycle_status='ACTIVE',created_at=now,updated_at=now))
        insert('upload_batches',dict(id=batch,work_id=work,member_id=1,upload_type='SETTING_BOOK'if case=='book-only'else'MULTI_EPISODE_SINGLE_FILE',source_type='TEXT_PASTE',status='COMPLETED',file_count=1,created_at=now,updated_at=now))
        content='합성 인물은 합성 도시에 살고 있다. 이 원문은 로컬 기능 검증용이다.';key='analysis-list-correctness/'+str(book)+'/revision-1.txt';target=root/key;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(content)
        digest=hashlib.sha256(content.encode()).hexdigest()
        insert('upload_files',dict(id=book,batch_id=batch,file_role='SETTING_BOOK',original_filename='합성 설정집.txt',mime_type='text/plain',storage_url='s3://'+key,content_storage_url='s3://'+key,file_size=len(content.encode()),parse_status='PARSED',created_at=now,updated_at=now))
        insert('setting_book_revisions',dict(id=revision,setting_book_id=book,revision_no=1,content_s3_key=key,content_hash=digest,char_count=len(content),reference_time_kind='WORK_START',created_at=now,updated_at=now))
        cur.execute('update upload_files set current_setting_book_revision_id=%s where id=%s',(revision,book))
        completed=case=='book-only'
        insert('analysis_jobs',dict(id=job,work_id=work,batch_id=batch,source_kind='SETTING_BOOK',source_setting_book_id=book,source_setting_book_revision_id=revision,job_type='SETTING_EXTRACTION',status='SUCCEEDED'if completed else'FAILED',failure_code=None if completed else'UNEXPECTED_ERROR',analysis_mode='ORDERED_PROVISIONAL',review_mode='AUTOMATIC',analysis_run_id=ident('correctness-run-'+case),run_generation=1,run_sequence=0,run_base_state=Jsonb({'characters':{},'worldSettings':{},'references':{}}),input_state_hash='c'*64 if completed else None,source_content_hash=digest,source_content_s3_key=key,state_journal=Jsonb({'changes':[],'outputStateHash':'d'*64})if completed else None,journal_status='SEALED'if completed else'INCOMPLETE',automatic_applied_at=now if completed else None,created_at=now,updated_at=now))
        if completed:
            insert('setting_candidates',dict(id=ident('correctness-character'),work_id=work,analysis_job_id=job,source_kind='SETTING_BOOK',source_setting_book_id=book,source_setting_book_revision_id=revision,entity_type='CHARACTER',entity_name='합성 인물',match_status='UNRESOLVED',candidate_kind='SETTING',attribute_name='profile.성격',attribute_value='차분하다',value_type='STRING',review_status='PENDING_REVIEW',comparison_status='COMPLETED',suggested_operation='ADD',created_at=now,updated_at=now))
            insert('world_setting_candidates',dict(id=ident('correctness-world'),work_id=work,analysis_job_id=job,source_kind='SETTING_BOOK',source_setting_book_id=book,source_setting_book_revision_id=revision,category='LOCATION',subject_name='합성 도시',setting_name='분위기',extracted_value='고요하다',evidence_spans=Jsonb([]),review_status='PENDING_REVIEW',comparison_status='COMPLETED',suggested_operation='ADD',created_at=now,updated_at=now))
        else:
            ep=ident('correctness-episode');epjob=ident('correctness-episode-job')
            insert('episodes',dict(id=ep,work_id=work,episode_no=1,title='합성 1화',content_s3_key=key,content_hash=digest,char_count=len(content),status='ANALYZED',content_updated_at=now,created_at=now,updated_at=now))
            insert('analysis_jobs',dict(id=epjob,work_id=work,batch_id=batch,episode_id=ep,job_type='SETTING_EXTRACTION',status='FAILED',failure_code='UNEXPECTED_ERROR',source_kind='EPISODE',created_at=now,updated_at=now))
            insert('analysis_job_episode_targets',dict(analysis_job_id=epjob,episode_id=ep))
        result['works'][case]={'workId':str(work),'batchId':str(batch),'settingBookId':str(book),'revisionId':str(revision),'sourceAnalysisJobId':str(job),'episodeJobId':str(ident('correctness-episode-job'))if case=='mixed'else None}
    a.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print(json.dumps({'preparedCases':cases,'manifest':str(a.output)},ensure_ascii=False))
