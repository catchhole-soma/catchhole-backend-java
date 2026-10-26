#!/usr/bin/env python3
"""Separately capture actual list PostgreSQL SELECTs and EXPLAIN, outside timing runs."""
import argparse,datetime,json,re,subprocess,time,urllib.request
from pathlib import Path
import psycopg
p=argparse.ArgumentParser();p.add_argument('--credentials',type=Path,required=True);p.add_argument('--dsn',required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--routes',default='job-one,jobs-thirty,comparison,episodes');p.add_argument('--phase',choices=['before','after'],default='before');a=p.parse_args()
creds=json.loads(a.credentials.read_text());base=creds['api']
if not base.startswith('http://127.0.0.1:28480') or '@127.0.0.1:26432/analysis_progress_benchmark' not in a.dsn:raise ValueError('Dedicated localhost runtime only')
def api(route,body=None,token=None):
    req=urllib.request.Request(base+'/api/v1'+route,data=json.dumps(body).encode() if body else None,headers={'Content-Type':'application/json',**({'Authorization':'Bearer '+token} if token else {})})
    with urllib.request.urlopen(req,timeout=60) as res:return res.read()
token=json.loads(api('/auth/login',{'email':creds['email'],'password':creds['password']}))['data']['accessToken']
a.output.mkdir(parents=True,exist_ok=True);index=[]
with psycopg.connect(a.dsn,autocommit=True) as c,c.cursor() as cur:
    cur.execute("ALTER SYSTEM SET log_min_duration_statement = 0");cur.execute('SELECT pg_reload_conf()');time.sleep(.2)
    try:
        routes={'job-one':['/analysis-jobs/'+creds['jobIds'][0]] if a.phase=='before' else ['/analysis-jobs/progress?analysisJobIds='+creds['jobIds'][0]],'jobs-thirty':['/analysis-jobs/'+x for x in creds['jobIds']] if a.phase=='before' else ['/analysis-jobs/progress?analysisJobIds='+','.join(creds['jobIds'])],'comparison':['/world-setting-candidates?batchId='+creds['batchId']+'&page=0&size=1'] if a.phase=='before' else ['/world-setting-candidates/progress?batchId='+creds['batchId']],'episodes':['/episodes']}
        if any(label not in routes for label in a.routes.split(',')): raise ValueError('Unknown capture route')
        for label in a.routes.split(','):
            since=datetime.datetime.now(datetime.timezone.utc).isoformat()
            for suffix in routes[label]: api('/works/'+creds['workId']+suffix,token=token)
            time.sleep(.1)
            raw=subprocess.check_output(['docker','logs','--since',since,'analysis-progress-benchmark-20261024-postgres'],stderr=subprocess.STDOUT,text=True)
            (a.output/f'{label}-postgres.log').write_text(raw)
            entries=re.split(r'(?m)^(?=\d{4}-\d\d-\d\d \d\d:\d\d:\d\d)',raw)
            current=None;queries=[];expect_parameters=False
            for entry in entries:
                match=re.search(r'duration: [\d.]+ ms\s+(?:execute [^:]+|statement):\s*((?:select|with)\b.*)',entry,re.S|re.I)
                if match:
                    current=match.group(1).strip();queries.append([current,{}]);expect_parameters=True
                elif 'DETAIL:  parameters:' not in entry: expect_parameters=False
                if 'DETAIL:  parameters:' in entry and queries and expect_parameters:
                    paramtext=entry.split('DETAIL:  parameters:',1)[1].strip()
                    expect_parameters=False
                    queries[-1][1]={int(n):v for n,v in re.findall(r"\$(\d+) = ('(?:[^']|'')*'|NULL)(?:, |$)",paramtext)}
            seen=set()
            for sql,params in queries:
                # A DEBUG detail entry may have been included with SQL in one log record.
                sql=re.split(r'\n\d{4}-\d\d-\d\d ',sql)[0]
                sql=sql.split('\nDETAIL:')[0].strip()
                # Preserve every executed read; identical SQL at different binds remains a separate query.
                seen.add(sql)
                bound=re.sub(r'\$(\d+)',lambda m:params.get(int(m.group(1)),m.group(0)),sql)
                number=len(index);path=a.output/f'{number:02d}-{label}.sql';path.write_text(bound+';\n')
                item={'number':number,'route':label,'sqlFile':path.name,'includesAnalysisJson':any(x in sql for x in ['automatic_input_state','run_base_state','state_journal']),'parametersBound':not bool(re.search(r'\$\d+',bound))}
                if item['parametersBound']:
                    try:
                        cur.execute('EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) '+bound);plan=cur.fetchone()[0];(a.output/f'{number:02d}-{label}-explain.json').write_text(json.dumps(plan,indent=2)+'\n');item['planningMs']=plan[0]['Planning Time'];item['executionMs']=plan[0]['Execution Time']
                    except Exception as e:item['explainError']=str(e)
                index.append(item)
    finally:
        cur.execute('ALTER SYSTEM RESET log_min_duration_statement');cur.execute('SELECT pg_reload_conf()')
(a.output/'index.json').write_text(json.dumps(index,indent=2)+'\n');print(json.dumps({'capturedSelects':len(index),'jsonSelectingQueries':sum(x['includesAnalysisJson'] for x in index),'explainFailures':sum('explainError'in x or not x['parametersBound'] for x in index)}))
