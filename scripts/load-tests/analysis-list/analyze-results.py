#!/usr/bin/env python3
"""Account for all samples, compare identical windows, and label bytes precisely."""
import argparse,collections,json,math,re,statistics
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--phase',type=Path,required=True);a=p.parse_args();root=a.phase

def lines(name):return [json.loads(x)for x in (root/(name+'.jsonl')).read_text().splitlines()]
def stats(values):
 values=sorted(x for x in values if x is not None)
 return {'n':len(values),'median':statistics.median(values)if values else None,'p95':values[math.ceil(len(values)*.95)-1]if values else None,'max':max(values)if values else None}
pages=lines('pages');apis=lines('api');resources=lines('resources');end=(root/'summary.json').stat().st_mtime
window=[r for r in resources if r['epochSeconds']<=end];measured=[x for x in pages if x['kind']=='navigation'];network=[r for page in pages for r in page['requests']];preflight=[r for page in pages for r in page['cdpPreflights']]
routes=collections.defaultdict(list)
for sample in measured:
 for req in sample['requests']:
  if '/api/v1/'in req['url']:
   path=req['url'].split('/api/v1')[1].split('?')[0];path=re.sub(r'/[0-9a-f-]{36}(?=/|$)','/:id',path)
   routes[req['method']+' '+path].append(req)
def heap(r):return sum((r.get('jstatGc',{}).get(k)or 0)for k in ['S0U','S1U','EU','OU'])/1024
def peak(items,key):return max((key(r)for r in items if not r.get('processUnavailable')),default=None)
report={'benchmarkCompletedAtEpochSeconds':end,'completionClockSource':'summary.json filesystem mtime immediately after runner final write','pageSampleAccounting':dict(collections.Counter(x['kind']for x in pages)),'apiSampleAccounting':dict(collections.Counter(x['kind']for x in apis)),'pageFailures':sum(not x['success']for x in pages),'apiFailures':sum(not x['success']for x in apis),'uniqueMeasuredTimeOrigins':len({x['timeOriginEpochMs']for x in measured}),'playwrightRequestsAllSamples':len(network),'censored':sum(x['censored']for x in network),'collectorErrors':sum(len(x['collectorErrors'])for x in pages),'finishedAfterReady':sum(x['finishedAfterReady']for x in network),'cdpPreflightsAllSamples':len(preflight),'preflightFailures':sum(x.get('failed',False)or not x['finished']or x.get('status',0)>=400 for x in preflight),'preflightTransferBytes':'not fully observable; encodedDataLength=0 does not mean zero wire bytes','apiPerMeasuredNavigation':stats([sum('/api/v1/'in r['url']for r in x['requests'])for x in measured]),'encodedApiResponseBodyBytesPerNavigation':stats([sum(r.get('sizes',{}).get('responseBodySize',0)for r in x['requests']if '/api/v1/'in r['url'])for x in measured]),'observedApiResponseHeaderBytesPerNavigation':stats([sum(r.get('sizes',{}).get('responseHeadersSize',0)for r in x['requests']if '/api/v1/'in r['url'])for x in measured]),'allPlaywrightResponseBodyBytesPerNavigation':stats([sum(r.get('sizes',{}).get('responseBodySize',0)for r in x['requests'])for x in measured]),'routes':{route:{'observations':len(reqs),'callsPerNavigation':len(reqs)/len(measured),'statusCounts':dict(collections.Counter(str(r.get('status'))for r in reqs)),'encodedResponseBodyBytes':stats([r.get('sizes',{}).get('responseBodySize')for r in reqs]),'responseEndMs':stats([r.get('timing',{}).get('responseEnd')for r in reqs])}for route,reqs in routes.items()},'resources':{'rawSamples':len(resources),'measurementWindowSamples':len(window),'unavailableSamples':sum(x.get('processUnavailable',False)for x in window),'measurementWindowPeakRssMiB':peak(window,lambda r:r['rssKiB']/1024),'measurementWindowPeakHeapUsedMiB':peak(window,heap),'rawWindowPeakRssMiB':peak(resources,lambda r:r['rssKiB']/1024),'rawWindowPeakHeapUsedMiB':peak(resources,heap),'gcJstatDelta':{key:(window[-1]['jstatGc'].get(key)or 0)-(window[0]['jstatGc'].get(key)or 0)for key in ['YGC','YGCT','FGC','FGCT','CGC','CGCT','GCT']}if window else {}},'reloadProtocol':'5 sets of two serial reloads; each waits for ready/load+drain before the next reload; no concurrent-load/OOM-reproduction claim'}
(root/'accounting.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(json.dumps({'phase':str(root),'pageFailures':report['pageFailures'],'apiFailures':report['apiFailures'],'apiCallsMedian':report['apiPerMeasuredNavigation']['median'],'resources':report['resources']},ensure_ascii=False))
