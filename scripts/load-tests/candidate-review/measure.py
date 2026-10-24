#!/usr/bin/env python3
"""Own one fresh Java process at a time and retain timing/resource provenance."""
import argparse
import hashlib
import json
import os
import platform
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('--root', required=True, type=Path)
p.add_argument('--phase', required=True, choices=['before', 'after'])
p.add_argument('--jar', required=True, type=Path)
p.add_argument('--routes', default='character,world,summary')
a = p.parse_args()
scripts = Path(__file__).resolve().parent
env = dict(os.environ)
env['JAVA_HOME'] = '/Users/junewoo/Library/Java/JavaVirtualMachines/ms-21.0.12.1/Contents/Home'
env['PATH'] = env['JAVA_HOME'] + '/bin:' + env['PATH']
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
for route in a.routes.split(','):
    if route not in ['character', 'world', 'summary']:
        raise ValueError('Unknown route')
    output = a.root / 'evidence' / a.phase / route
    output.mkdir(parents=True, exist_ok=True)
    if (output / 'summary.json').exists():
        raise RuntimeError('Refusing to overwrite completed timing evidence')
    log_path = a.root / 'runtime' / f'{a.phase}-{route}-api.log'
    with log_path.open('w') as log:
        java = subprocess.Popen(['sh', str(scripts / 'run-api.sh'), str(a.root), a.phase + '/' + route, str(a.jar)], stdout=log, stderr=subprocess.STDOUT, env=env)
        sampler = None
        try:
            ready = False
            for _ in range(160):
                if java.poll() is not None:
                    raise RuntimeError('Owned Java process exited during startup')
                try:
                    with urllib.request.urlopen('http://127.0.0.1:29480/actuator/health', timeout=1) as response:
                        ready = response.status == 200
                    if ready:
                        break
                except Exception:
                    pass
                time.sleep(.25)
            if not ready:
                raise RuntimeError('Owned Java process did not become healthy')
            limits = json.loads(subprocess.check_output(['docker', 'inspect', '--format', '{{json .HostConfig}}', 'candidate-review-benchmark-20261025-postgres'], text=True))
            manifest = {'phase': a.phase, 'route': route, 'jarSha256': sha(a.jar), 'jarPath': str(a.jar.resolve()),
                        'baselineBackendCommit': '64eda0e3f37a07a82616bb01733f947a03547c19',
                        'baselineFrontendCommit': '36a143391e8f330ac88c910517585a263626d3e0',
                        'pid': java.pid, 'os': platform.platform(), 'machine': platform.machine(),
                        'hostLogicalCpus': subprocess.check_output(['sysctl', '-n', 'hw.logicalcpu'], text=True).strip(),
                        'hostMemoryBytes': subprocess.check_output(['sysctl', '-n', 'hw.memsize'], text=True).strip(),
                        'jvmFlags': subprocess.check_output(['jcmd', str(java.pid), 'VM.flags'], text=True, env=env),
                        'javaVersion': subprocess.check_output(['java', '-version'], text=True, stderr=subprocess.STDOUT, env=env),
                        'postgresVersion': subprocess.check_output(['docker', 'exec', 'candidate-review-benchmark-20261025-postgres', 'psql', '-U', 'candidate_review_bench', '-d', 'candidate_review_benchmark', '-Atc', 'select version()'], text=True).strip(),
                        'databaseContainerLimits': {key: limits[key] for key in ['NanoCpus', 'Memory', 'MemorySwap', 'CpuQuota', 'CpuPeriod', 'CpusetCpus', 'ShmSize']},
                        'fixture': json.loads((a.root / 'evidence/fixture.json').read_text()),
                        'scriptSha256': {path.name: sha(path) for path in scripts.iterdir() if path.is_file()},
                        'cacheProtocol': 'Fresh Java per route; 3 sequential warmup requests then 30 measured requests. OS/DB caches not reset. No concurrent builds/tests or SQL debug.',
                        'resourceSampling': 'RSS KiB and jstat -gc every 0.5s; observed peaks only; GC collector time is not pause time',
                        'isolation': 'localhost PG27432/API29480/FE3440; native Xms256m/Xmx768m; ExitOnOutOfMemoryError; no worker, dotenv, compose, tracing or schedulers'}
            (output / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
            sampler = subprocess.Popen([sys.executable, str(scripts / 'sample-resources.py'), str(java.pid), str(output / 'resources.jsonl'), str(output / 'summary.json')], env=env)
            result = subprocess.run([sys.executable, str(scripts / 'run.py'), '--credentials', str(a.root / 'runtime/credentials.json'), '--output', str(output), '--phase', a.phase, '--route', route])
            if result.returncode:
                raise RuntimeError(f'Runner failed for {route}')
        finally:
            if sampler:
                sampler.terminate()
                sampler.wait(timeout=5)
            if java.poll() is None:
                java.terminate()
                try:
                    java.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    java.kill()
                    java.wait(timeout=5)
            (output / 'process.json').write_text(json.dumps({'ownedJavaPid': java.pid, 'exitCode': java.returncode, 'apiLog': str(log_path), 'stoppedAfterSeries': True}, indent=2) + '\n')
        print(json.dumps({'phaseCompleted': a.phase, 'route': route, 'ownedJavaStopped': True}), flush=True)
