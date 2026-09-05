// 운영 분석 전용 수동 실행기. Node.js 기본 기능만 사용하며 다음 단계로 자동 확대하지 않는다.
import * as fs from 'node:fs';
import { createHash } from 'node:crypto';
import { parseArgs } from 'node:util';
import { setTimeout as sleep } from 'node:timers/promises';
import assert from 'node:assert/strict';

const BASE = 'https://api.catchhole.com';
const TOKEN_FILE = '/private/tmp/catchhole-load-test-token';
const FIXTURES = new Map([
  ['ed8dbecac35a35d67ed6025aeea4b8b1c7c08d07191835111f9c56a08f9b332a', { alias: 'fixture-004', episodeNo: 4 }],
  ['3f1929ebd8fc05c8486282a25960e2e92b6fdea400d8c4fb116d2ce0bca5992a', { alias: 'fixture-005', episodeNo: 5 }],
]);
const TERMINAL = new Set(['SUCCEEDED', 'FAILED', 'CANCELED']);
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const { values: args, positionals } = parseArgs({ allowPositionals: true, options: {
  file: { type: 'string' }, dir: { type: 'string' }, count: { type: 'string', default: '10' },
  minutes: { type: 'string', default: '20' }, 'budget-note': { type: 'string' },
} });
const mode = positionals[0];
const count = Number(args.count);
const minutes = Number(args.minutes);
let state, dir, stopped = false;
const now = () => new Date().toISOString();
const hash = value => createHash('sha256').update(value).digest('hex');
const log = (event, fields = {}) => console.log(JSON.stringify({ at: now(), event, ...fields }));
const need = (condition, code) => { if (!condition) throw new Error(code); };
const pick = (object, keys) => Object.fromEntries(keys.map(key => [key, object[key]]));
const failure = error => ({ code: /^[A-Z_0-9]+$/.test(error.message) ? error.message : 'LOCAL_ERROR', status: error.status ?? null });
const jobFields = ['id', 'workId', 'batchId', 'episodeId', 'jobType', 'status', 'createdAt', 'startedAt', 'completedAt', 'inputTokenCount', 'outputTokenCount', 'modelName', 'failureCode', 'tokenInterruptedAfterExtraction'];

function validateInputManifest(input) {
  const fixture = FIXTURES.get(input?.sha256);
  // 최초 4화 기록에는 episodeNo가 없으므로 그 형식만 하위 호환한다.
  need(fixture && input.alias === fixture.alias && (input.episodeNo ?? 4) === fixture.episodeNo, 'MANIFEST_INVALID');
}

function token() {
  need((fs.statSync(TOKEN_FILE).mode & 0o077) === 0, 'TOKEN_FILE_PERMISSIONS');
  const value = fs.readFileSync(TOKEN_FILE, 'utf8').trim();
  need(value.split('.').length === 3 && !/\s/.test(value), 'TOKEN_FORMAT');
  let payload;
  try { payload = JSON.parse(Buffer.from(value.split('.')[1], 'base64url').toString()); }
  catch { throw new Error('TOKEN_FORMAT'); }
  need(typeof payload.sub === 'string' && Number.isFinite(payload.exp), 'TOKEN_FORMAT');
  const remainingSeconds = Math.floor(payload.exp - Date.now() / 1000);
  need(remainingSeconds > 15, 'TOKEN_EXPIRED');
  const ownerHash = hash(payload.sub);
  need(!state?.ownerHash || state.ownerHash === ownerHash, 'TOKEN_ACCOUNT_CHANGED');
  return { value, remainingSeconds, ownerHash };
}

async function api(path, { method = 'GET', body, category = 'poll', auth = true } = {}) {
  const start = performance.now();
  const headers = { Accept: 'application/json', 'User-Agent': 'CatchHole-Analysis-Load-Test' };
  if (auth) headers.Authorization = `Bearer ${token().value}`;
  if (body && !(body instanceof FormData)) { headers['Content-Type'] = 'application/json'; body = JSON.stringify(body); }
  let status = null, ok = false;
  try {
    const response = await fetch(BASE + path, { method, body, headers, redirect: 'error', signal: AbortSignal.timeout(30000) });
    status = response.status;
    let result;
    try { result = await response.json(); } catch { throw Object.assign(new Error('RESPONSE_FORMAT'), { status }); }
    if (!response.ok || (auth && result.success !== true)) throw Object.assign(new Error('HTTP_ERROR'), { status });
    ok = true;
    return { data: auth ? result.data : result, durationMs: performance.now() - start };
  } catch (error) {
    if (error.name === 'TimeoutError' || error.name === 'AbortError') throw new Error('REQUEST_TIMEOUT');
    if (error instanceof TypeError) throw new Error('NETWORK_ERROR');
    throw error;
  } finally {
    if (state) state.requests.push({ at: now(), category, method, status, ok, durationMs: performance.now() - start });
  }
}

async function health() {
  const result = await api('/actuator/health', { auth: false, category: 'health' });
  need(result.data.status === 'UP', 'HEALTH_NOT_UP');
}

async function usage() {
  const { data } = await api('/api/v1/ai-token-usages/me', { category: 'quota' });
  for (const key of ['grantedTokens', 'usedTokens', 'reservedTokens', 'remainingTokens']) need(Number.isSafeInteger(data[key]), 'QUOTA_FORMAT');
  return pick(data, ['grantedTokens', 'usedTokens', 'reservedTokens', 'remainingTokens', 'exhausted']);
}

function save() {
  fs.writeFileSync(dir + '/state.json.next', JSON.stringify(state, null, 2) + '\n', { mode: 0o600 });
  fs.renameSync(dir + '/state.json.next', dir + '/state.json');
}

function distribution(samples) {
  const sorted = samples.filter(Number.isFinite).sort((a, b) => a - b);
  const n = sorted.length;
  if (!n) return { n: 0, avg: null, median: null, p95: null, p99: null, max: null };
  const rank = p => sorted[Math.ceil(p * n) - 1];
  return { n, avg: sorted.reduce((a, b) => a + b, 0) / n, median: n % 2 ? sorted[(n - 1) / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2, p95: rank(.95), p99: rank(.99), max: sorted[n - 1] };
}

function serverTime(value) {
  if (!value) return NaN;
  need(/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?$/.test(value), 'SERVER_TIME_FORMAT');
  return Date.parse(value + '+09:00'); // 실행 전 APP_TIMEZONE=Asia/Seoul 확인. 클라이언트 시각과 혼용 금지.
}

function acceptJob(record, job) {
  need(UUID.test(job.id) && job.workId === record.workId && job.batchId === record.batchId && job.episodeId === record.episodeId && job.jobType === 'SETTING_EXTRACTION', 'JOB_TARGET_MISMATCH');
  need(['PENDING', 'RUNNING', ...TERMINAL].includes(job.status), 'JOB_STATUS_UNKNOWN');
  need(!record.job || record.job.id === job.id, 'JOB_ID_CHANGED');
  if (record.job?.startedAt && job.startedAt !== record.job.startedAt) record.retryObserved = true;
  if (record.job?.status === 'RUNNING' && job.status === 'PENDING') record.retryObserved = true;
  record.job = pick(job, jobFields);
  if (TERMINAL.has(job.status) && !record.observedTerminalAt) record.observedTerminalAt = now();
}

function summarize() {
  const records = state.records;
  const successful = records.filter(r => r.job?.status === 'SUCCEEDED');
  const timed = successful.filter(r => !r.retryObserved && r.job.startedAt && r.job.completedAt);
  const differences = (end, start) => timed.map(r => serverTime(r.job[end]) - serverTime(r.job[start])).filter(v => v >= 0);
  const statuses = Object.fromEntries(['PENDING', 'RUNNING', ...TERMINAL, 'UNKNOWN'].map(s => [s, records.filter(r => (r.job?.status ?? 'UNKNOWN') === s).length]));
  const groups = [...new Set(state.requests.map(r => r.category))];
  const finalAt = state.finishedAt ?? now();
  return {
    runId: state.runId, target: BASE, generatedAt: now(), phase: state.phase, plannedJobs: state.count,
    input: state.input, startedAt: state.startedAt ?? null, finishedAt: state.finishedAt ?? null,
    observationDeadline: state.deadline ?? null, statuses,
    verificationPassed: records.filter(r => r.verification?.ok).length,
    retryObservedJobs: records.filter(r => r.retryObserved).length,
    maximumSampledRunning: Math.max(0, ...state.samples.map(s => s.running)),
    queueMs: distribution(differences('startedAt', 'createdAt')),
    executionMs: distribution(differences('completedAt', 'startedAt')),
    jobTotalMs: distribution(differences('completedAt', 'createdAt')),
    observedCompletionMs: distribution(successful.filter(r => r.observedTerminalAt && r.submittedAt).map(r => Date.parse(r.observedTerminalAt) - Date.parse(r.submittedAt))),
    stageElapsedMs: state.startedAt ? Date.parse(finalAt) - Date.parse(state.startedAt) : null,
    submissionWindowMs: records.every(r => r.submittedAt) ? Math.max(...records.map(r => Date.parse(r.submittedAt))) - Math.min(...records.map(r => Date.parse(r.submittedAt))) : null,
    http: Object.fromEntries(groups.map(category => { const requests = state.requests.filter(r => r.category === category); return [category, { requests: requests.length, failures: requests.filter(r => !r.ok).length, ms: distribution(requests.map(r => r.durationMs)) }]; })),
    tokenTotals: {
      scope: 'all_observed_jobs_reported_usage_not_provider_invoice',
      input: records.reduce((n, r) => n + (r.job?.inputTokenCount ?? 0), 0),
      output: records.reduce((n, r) => n + (r.job?.outputTokenCount ?? 0), 0),
      missingUsageJobs: records.filter(r => !Number.isSafeInteger(r.job?.inputTokenCount) || !Number.isSafeInteger(r.job?.outputTokenCount)).length,
    },
    quotaAtSubmission: state.quotaAtSubmission ?? null,
    quotaAfter: state.quotaAfter ?? null,
    jobs: records.map(r => ({ index: r.index, status: r.job?.status ?? 'UNKNOWN', failureCode: r.job?.failureCode ?? null, submitError: r.submitError ?? null, verification: r.verification ?? null, pollError: r.pollError ?? null })),
    limitations: ['동일 계정·원문 반복', 'HTTP 시간에 연결 수립과 JSON 수신/해석 포함; 기존 k6 조회 timing과 구분', 'RUNNING은 목표 5초의 순차 조회 합계로 동시 snapshot이 아님; 실제 동시성보다 크거나 작을 수 있어 서버 시작·종료 구간으로 별도 검증', 'CPU·메모리·DB·실제 과금은 실행기 밖에서 관찰', '서버 timestamp는 Asia/Seoul 가정; 재시도 관측 Job은 서버 시간 통계 제외'],
    passed: records.length === state.count && records.every(r => r.job?.status === 'SUCCEEDED' && r.verification?.ok && !r.submitError && !r.retryObserved) && state.requests.every(r => r.ok),
  };
}

function writeSummary() {
  fs.writeFileSync(dir + '/summary.json', JSON.stringify(summarize(), null, 2) + '\n', { mode: 0o600 });
}

async function prepare() {
  need([10, 50, 100].includes(count) && args.file, 'PREPARE_ARGUMENTS');
  const input = fs.readFileSync(args.file);
  const inputHash = hash(input);
  const fixture = FIXTURES.get(inputHash);
  need(fixture, 'INPUT_HASH_MISMATCH');
  const identity = token();
  need(identity.remainingSeconds > 300, 'TOKEN_TOO_CLOSE_TO_EXPIRY');
  await health();
  const quota = await usage();
  need(!quota.exhausted && quota.remainingTokens > 0, 'QUOTA_EXHAUSTED');
  dir = fs.mkdtempSync('/private/tmp/catchhole-analysis-');
  fs.chmodSync(dir, 0o700);
  state = { runId: `analysis-${new Date().toISOString().replace(/[-:.]/g, '')}-${count}`, phase: 'PREPARING', count, ownerHash: identity.ownerHash, createdAt: now(), input: { ...fixture, bytes: input.length, sha256: inputHash }, quotaBefore: quota, records: [], requests: [], samples: [] };
  save(); log('PREPARING', { directory: dir, count });
  for (let index = 1; index <= count; index++) {
    need(!stopped, 'STOPPED');
    const record = { index, title: `부하테스트-${state.runId}-${index}` };
    state.records.push(record); save(); // 응답 불명확 시 title로 수동 확인, 자동 재생성하지 않는다.
    const work = await api('/api/v1/works', { method: 'POST', category: 'createWork', body: { title: record.title, genre: '판타지', description: '운영 분석 부하 테스트 전용' } });
    need(UUID.test(work.data.id), 'WORK_RESPONSE_FORMAT');
    record.workId = work.data.id; save();
    const form = new FormData();
    form.set('metadata', new Blob([JSON.stringify({ uploadType: 'SINGLE_EPISODE', singleEpisodeNo: fixture.episodeNo })], { type: 'application/json' }), 'metadata.json');
    form.append('episodeFiles', new Blob([input], { type: 'text/plain' }), `${fixture.alias}.txt`);
    const upload = await api(`/api/v1/works/${record.workId}/episodes`, { method: 'POST', body: form, category: 'upload' });
    need(upload.data.status === 'COMPLETED' && upload.data.episodeCount === 1 && upload.data.createdEpisodes?.length === 1 && UUID.test(upload.data.batchId), 'UPLOAD_RESPONSE_FORMAT');
    const episode = upload.data.createdEpisodes[0];
    need(UUID.test(episode.id) && episode.episodeNo === fixture.episodeNo, 'EPISODE_RESPONSE_FORMAT');
    record.batchId = upload.data.batchId; record.episodeId = episode.id; record.charCount = episode.charCount;
    save(); log('PREPARED_WORK', { completed: index, total: count });
  }
  state.phase = 'PREPARED'; save(); writeSummary(); log('PREPARED', { directory: dir, count, paidAnalysisSubmitted: 0 });
}

async function verifyResult(record) {
  const root = `/api/v1/works/${record.workId}`;
  const episode = (await api(`${root}/episodes/${record.episodeId}`, { category: 'verify' })).data;
  need(episode.id === record.episodeId && episode.status === 'ANALYZED' && typeof episode.content === 'string' && episode.content.length > 0, 'EPISODE_RESULT_INVALID');
  const characterStatuses = {};
  let seen = 0, total = 0;
  for (let page = 0; page < 100; page++) {
    const data = (await api(`${root}/setting-candidates?batchId=${record.batchId}&size=100&page=${page}`, { category: 'verify' })).data;
    const candidates = data.candidates;
    need(Array.isArray(candidates?.content) && Number.isSafeInteger(candidates.totalElements) && typeof candidates.hasNext === 'boolean', 'CANDIDATE_PAGE_INVALID');
    total = candidates.totalElements;
    for (const candidate of candidates.content) {
      need(['NOT_REQUIRED', 'WAITING_FOR_CHARACTER_MATCH', 'PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'RECOMPARISON_REQUIRED'].includes(candidate.comparisonStatus), 'COMPARISON_STATUS_UNKNOWN');
      characterStatuses[candidate.comparisonStatus] = (characterStatuses[candidate.comparisonStatus] ?? 0) + 1;
      seen++;
    }
    if (!candidates.hasNext) break;
  }
  need(seen === total, 'CANDIDATE_PAGE_INCOMPLETE');
  const world = (await api(`${root}/world-setting-candidates?batchId=${record.batchId}&size=1`, { category: 'verify' })).data;
  const worldKeys = ['totalCandidateCount', 'pendingComparisonCount', 'processingComparisonCount', 'activeComparisonJobCount', 'failedComparisonCount', 'tokenInterruptedComparisonCount', 'recomparisonRequiredCount'];
  for (const key of worldKeys) need(Number.isSafeInteger(world[key]) && world[key] >= 0, 'WORLD_COUNTS_INVALID');
  return { episodeStatus: episode.status, characterCount: seen, characterStatuses, world: pick(world, worldKeys),
    ok: !record.job.tokenInterruptedAfterExtraction && ['FAILED', 'PENDING', 'PROCESSING', 'RECOMPARISON_REQUIRED'].every(s => !characterStatuses[s]) && worldKeys.slice(1).every(k => world[k] === 0) };
}

async function refresh(record) {
  if (!record.workId || !record.episodeId) return;
  const root = `/api/v1/works/${record.workId}/analysis-jobs`;
  if (!record.job) {
    const { data } = await api(root);
    need(Array.isArray(data), 'JOB_LIST_INVALID');
    const found = data.filter(j => j.batchId === record.batchId && j.episodeId === record.episodeId && j.jobType === 'SETTING_EXTRACTION');
    need(found.length <= 1, 'AMBIGUOUS_JOB');
    if (found.length) acceptJob(record, found[0]);
  } else if (!TERMINAL.has(record.job.status)) acceptJob(record, (await api(`${root}/${record.job.id}`)).data);
  if (record.job?.status === 'SUCCEEDED' && !record.verification) record.verification = await verifyResult(record);
}

async function observe() {
  need(['SUBMITTING', 'OBSERVING', 'OBSERVATION_ENDED', 'FINISHED'].includes(state.phase), 'NOT_SUBMITTED');
  state.phase = 'OBSERVING';
  const deadline = Date.now() + minutes * 60000;
  state.deadline = new Date(deadline).toISOString(); save();
  while (Date.now() < deadline && !stopped) {
    const round = performance.now();
    await health();
    for (const record of state.records) {
      try { await refresh(record); delete record.pollError; }
      catch (error) { record.pollError = failure(error); log('OBSERVATION_ERROR', { index: record.index, ...record.pollError }); if ([401, 403].includes(error.status) || error.message.startsWith('TOKEN_')) throw error; }
      save();
    }
    const summary = summarize();
    const sample = { at: now(), running: summary.statuses.RUNNING, pending: summary.statuses.PENDING, succeeded: summary.statuses.SUCCEEDED, failed: summary.statuses.FAILED, canceled: summary.statuses.CANCELED, unknown: summary.statuses.UNKNOWN, verified: summary.verificationPassed };
    state.samples.push(sample); save(); writeSummary(); log('PROGRESS', sample);
    if (state.records.every(r => r.job && TERMINAL.has(r.job.status) && (r.job.status !== 'SUCCEEDED' || r.verification))) {
      state.phase = 'FINISHED'; state.finishedAt = now(); break;
    }
    await sleep(Math.max(0, 5000 - (performance.now() - round)));
  }
  if (state.phase !== 'FINISHED') state.phase = 'OBSERVATION_ENDED';
  try { state.quotaAfter = await usage(); } catch (error) { state.quotaAfterError = failure(error); }
  save(); writeSummary();
  log('OBSERVATION_END', { directory: dir, ...pick(summarize(), ['phase', 'statuses', 'verificationPassed', 'passed']) });
  if (!summarize().passed) process.exitCode = 2;
}

function assertReadyForSubmission(runState) {
  need([10, 50, 100].includes(runState.count) && runState.phase === 'PREPARED' && runState.records.length === runState.count && runState.records.every(r => UUID.test(r.workId) && UUID.test(r.batchId) && UUID.test(r.episodeId) && !r.job && !r.submittedAt), 'ALREADY_STARTED_OR_INCOMPLETE');
}

async function run() {
  assertReadyForSubmission(state);
  need(args['budget-note']?.length > 0, 'BUDGET_NOTE_REQUIRED');
  need(token().remainingSeconds > 300, 'TOKEN_TOO_CLOSE_TO_EXPIRY');
  await health();
  for (const record of state.records) { const existing = (await api(`/api/v1/works/${record.workId}/analysis-jobs`, { category: 'preflight' })).data; need(Array.isArray(existing) && existing.length === 0, 'EXISTING_JOB'); }
  state.quotaAtSubmission = await usage();
  need(!state.quotaAtSubmission.exhausted, 'QUOTA_EXHAUSTED');
  state.phase = 'SUBMITTING'; state.startedAt = now(); state.budgetNote = args['budget-note']; save();
  await Promise.allSettled(state.records.map(async record => {
    record.submittedAt = now(); save();
    try {
      const { data } = await api(`/api/v1/works/${record.workId}/analysis-jobs`, { method: 'POST', category: 'submit', body: { jobType: 'SETTING_EXTRACTION', batchId: record.batchId, episodeId: record.episodeId } });
      need(Array.isArray(data) && data.length === 1, 'JOB_RESPONSE_FORMAT'); acceptJob(record, data[0]);
    } catch (error) { record.submitError = failure(error); log('SUBMISSION_ERROR', { index: record.index, ...record.submitError }); }
    finally { save(); }
  }));
  log('SUBMITTED', { planned: state.count, knownJobs: state.records.filter(r => r.job).length });
  await observe();
}

function selfTest() {
  for (const [sha256, fixture] of FIXTURES) {
    const input = { ...fixture, sha256 };
    assert.doesNotThrow(() => validateInputManifest(input));
    assert.throws(() => validateInputManifest({ ...input, episodeNo: 99 }));
    assert.throws(() => validateInputManifest({ ...input, alias: 'wrong-fixture' }));
    if (fixture.episodeNo === 4) assert.doesNotThrow(() => validateInputManifest({ ...input, episodeNo: undefined }));
    else assert.throws(() => validateInputManifest({ ...input, episodeNo: undefined }));
  }
  assert.equal(FIXTURES.size, 2);
  assert.throws(() => validateInputManifest({ sha256: hash('unapproved input') }));
  assert.throws(() => validateInputManifest(undefined));
  assert.equal(distribution([1, 2, 3, 4, 5, 6, 7, 8, 9, 10]).p95, 10);
  assert.equal(distribution([]).p95, null);
  assert.equal(distribution([2, 4]).median, 3);
  assert.equal(serverTime('2026-09-05T18:00:00'), Date.parse('2026-09-05T09:00:00Z'));
  assert.throws(() => serverTime('invalid'));
  const id = '00000000-0000-0000-0000-000000000001';
  const record = { workId: id, batchId: id, episodeId: id };
  const ready = { phase: 'PREPARED', count: 10, records: Array.from({ length: 10 }, () => ({ ...record })) };
  assert.doesNotThrow(() => assertReadyForSubmission(ready));
  assert.throws(() => assertReadyForSubmission({ ...ready, phase: 'SUBMITTING' }));
  assert.throws(() => assertReadyForSubmission({ ...ready, records: ready.records.slice(1) }));
  assert.throws(() => assertReadyForSubmission({ ...ready, records: ready.records.map(r => ({ ...r, submittedAt: now() })) }));
  const job = { id, workId: id, batchId: id, episodeId: id, jobType: 'SETTING_EXTRACTION', status: 'RUNNING', startedAt: '2026-09-05T18:00:00', secret: 'must-not-persist' };
  acceptJob(record, job); assert.equal(record.job.secret, undefined);
  acceptJob(record, { ...job, status: 'PENDING', startedAt: null }); assert.equal(record.retryObserved, true);
  assert.throws(() => acceptJob(record, { ...job, workId: 'other' }));
  assert.throws(() => acceptJob(record, { ...job, status: 'INVENTED' }));
  assert.deepEqual(failure(new Error('secret URL')), { code: 'LOCAL_ERROR', status: null });
  state = { count: 3, requests: [], samples: [], records: [
    { job: { status: 'SUCCEEDED', inputTokenCount: 100, outputTokenCount: 20 } },
    { job: { status: 'FAILED', inputTokenCount: 50, outputTokenCount: 10 } },
    { job: { status: 'RUNNING' } },
  ] };
  assert.deepEqual(summarize().tokenTotals, {
    scope: 'all_observed_jobs_reported_usage_not_provider_invoice', input: 150, output: 30, missingUsageJobs: 1,
  });
  assert.equal(summarize().quotaAfter, null);
  state = undefined;
  log('SELF_TEST_PASS', { networkRequests: 0, tokenRead: false });
}

async function main() {
  need(['check', 'prepare', 'run', 'observe', 'self-test'].includes(mode), 'MODE_REQUIRED');
  need(Number.isFinite(minutes) && minutes >= 1 && minutes <= 60, 'INVALID_OBSERVATION_MINUTES');
  if (mode === 'self-test') return selfTest();
  if (mode === 'check') { const identity = token(); await health(); const quota = await usage(); return log('CHECK_PASS', { tokenRemainingSeconds: identity.remainingSeconds, quota }); }
  process.on('SIGINT', () => { stopped = true; log('STOP_REQUESTED_REMOTE_JOBS_CONTINUE'); });
  process.on('SIGTERM', () => { stopped = true; });
  if (mode === 'prepare') return prepare();
  need(/^\/private\/tmp\/catchhole-analysis-[A-Za-z0-9_-]+$/.test(args.dir ?? ''), 'INVALID_RUN_DIRECTORY');
  dir = args.dir;
  need(fs.lstatSync(dir).isDirectory() && (fs.statSync(dir).mode & 0o077) === 0, 'RUN_DIRECTORY_PERMISSIONS');
  const lock = fs.openSync(dir + '/run.lock', 'wx', 0o600);
  try {
    state = JSON.parse(fs.readFileSync(dir + '/state.json', 'utf8'));
    need([10, 50, 100].includes(state.count), 'MANIFEST_INVALID');
    validateInputManifest(state.input);
    token();
    if (mode === 'run') await run(); else await observe();
  } finally { fs.closeSync(lock); fs.unlinkSync(dir + '/run.lock'); }
}

main().catch(error => {
  if (state && dir) { state.lastError = failure(error); save(); writeSummary(); }
  log('STOPPED', { ...failure(error), directory: dir ?? null, remoteJobsMayContinue: Boolean(state?.startedAt) });
  process.exitCode = 1;
});
