#!/usr/bin/env python3
"""Copy reviewable synthetic evidence; never publishes API logs, credentials or test output."""
import argparse,hashlib,json,re,shutil
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--destination',type=Path,required=True);a=p.parse_args();dest=a.destination;dest.mkdir(parents=True,exist_ok=True)
(dest/'.gitignore').write_text('# Only these sanitized evidence logs override the repository log exclusion.\n!before/gc.log\n!before/sql/batches-postgres.log\n!before/sql/all-jobs-postgres.log\n!before/sql/episodes-postgres.log\n!after/gc.log\n!after/sql-list/batches-postgres.log\n!after/sql-compat/all-jobs-postgres.log\n!after/sql-compat/episodes-postgres.log\n')
measurement=str(a.root.resolve());user_home=str(Path.home())
def redact(text):return text.replace(measurement,'${MEASUREMENT_ROOT}').replace(user_home,'${USER_HOME}')
def copy(source,target):
 target.parent.mkdir(parents=True,exist_ok=True);target.write_text(redact(source.read_text()))
for phase in ['before','after']:
 source=a.root/'evidence'/phase
 for name in ['manifest.json','summary.json','accounting.json','pages.jsonl','api.jsonl','resources.jsonl','gc.log']:
  copy(source/name,dest/phase/name)
 for folder in (['sql']if phase=='before'else['sql-list','sql-compat']):
  for f in (source/folder).iterdir():
   if f.suffix in ('.sql','.json','.log'):copy(f,dest/phase/folder/f.name)
copy(a.root/'evidence/fixture.json',dest/'fixture.json')
copy(a.root/'evidence/full-tests/summary.json',dest/'verification/full-tests-summary.json')
copy(a.root/'evidence/schema-tests/summary.json',dest/'verification/schema-tests-summary.json')
copy(a.root/'evidence/focused-tests/source-sha256.json',dest/'verification/focused-source-sha256.json')
copy(a.root/'evidence/frontend-live/result.json',dest/'verification/frontend-live-result.json')
copy(a.root/'evidence/before-attempt2/manifest.json',dest/'provenance/original-build-manifest.json')
m=json.loads((dest/'before/manifest.json').read_text());m['artifactSourceProvenance']['originalManifest']='../provenance/original-build-manifest.json';m['sanitization']='Absolute workspace/home paths replaced; private credentials/API logs/test output excluded. Raw originals preserved outside repository.';(dest/'before/manifest.json').write_text(json.dumps(m,ensure_ascii=False,indent=2)+'\n')
credentials=json.loads((a.root/'secrets/credentials.json').read_text())
leaks=[]
for f in dest.rglob('*'):
 if not f.is_file():continue
 s=f.read_text()
 if credentials['password']in s or re.search(r'Bearer\s+eyJ|"(?:accessToken|refreshToken|Authorization|Cookie)"\s*:',s):leaks.append(str(f.relative_to(dest)))
if leaks:raise RuntimeError('Credential-like evidence detected in: '+','.join(leaks))
files={str(f.relative_to(dest)):hashlib.sha256(f.read_bytes()).hexdigest()for f in dest.rglob('*')if f.is_file()and f.name!='SHA256SUMS.json'}
(dest/'SHA256SUMS.json').write_text(json.dumps(files,indent=2)+'\n');print(json.dumps({'publishedFiles':len(files),'credentialScan':'passed','bytes':sum(f.stat().st_size for f in dest.rglob('*')if f.is_file())}))
