#!/usr/bin/env python3
"""Samples owned Java process. RSS is KiB; jstat columns are retained raw (KiB/seconds)."""
import json,signal,subprocess,sys,time,os
from pathlib import Path
pid=sys.argv[1];dest=Path(sys.argv[2]);stop=False
until_summary=Path(sys.argv[3]) if len(sys.argv)>3 else None
(dest.parent/'resource-sampler.pid').write_text(str(os.getpid())+'\n')

def end(*_):
    global stop;stop=True
signal.signal(signal.SIGTERM,end)
with dest.open('a',buffering=1) as out:
    while not stop:
        if until_summary and until_summary.exists(): break
        start=time.monotonic()
        try:
            ps=subprocess.check_output(['ps','-o','rss=,pcpu=','-p',pid],text=True).split()
            raw=subprocess.check_output(['jstat','-gc',pid],text=True,stderr=subprocess.DEVNULL).splitlines()
            fields=raw[0].split();values=raw[1].split();gc=dict(zip(fields,[None if x=='-' else float(x) for x in values]))
            out.write(json.dumps({'epochSeconds':time.time(),'rssKiB':int(ps[0]),'processCpuPercent':float(ps[1]),'jstatGc':gc})+'\n')
        except (subprocess.SubprocessError,IndexError):
            out.write(json.dumps({'epochSeconds':time.time(),'processUnavailable':True})+'\n');break
        time.sleep(max(0,.5-(time.monotonic()-start)))
