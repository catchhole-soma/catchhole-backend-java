import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Rate, Trend } from 'k6/metrics';

const baseUrl = 'https://api.catchhole.com';
const stages = { smoke: { vus: 1, iterations: 1 }, '1': { vus: 1, duration: '1m' }, '5': { vus: 5, duration: '2m' }, '10': { vus: 10, duration: '5m' } };
const stage = __ENV.STAGE || 'smoke';
if (!stages[stage]) throw new Error('STAGE는 smoke, 1, 5, 10 중 하나여야 합니다.');
const token = (__ENV.ACCESS_TOKEN || open(__ENV.TOKEN_FILE || '/private/tmp/catchhole-load-test-token')).trim();
if (!token || /\s/.test(token)) throw new Error('공백과 Bearer 접두사 없이 액세스 토큰만 입력하세요.');

const duration = new Trend('works_duration_ms', true);
const failures = new Rate('works_failures');
const params = {
  headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' },
  timeout: '5s',
  redirects: 0,
  tags: { name: 'works_list' },
};

export const options = {
  scenarios: {
    works: {
      executor: stage === 'smoke' ? 'shared-iterations' : 'constant-vus',
      ...stages[stage],
      gracefulStop: '10s',
    },
  },
  maxRedirects: 0,
  userAgent: 'CatchHole-Read-Load-Test',
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
  thresholds: {
    works_duration_ms: ['p(95)<1000'],
    works_failures: ['rate==0'],
  },
};

function validWorks(response) {
  try {
    const body = response.json();
    return response.status === 200 && body.success === true && Array.isArray(body.data) && body.data.length > 0;
  } catch {
    return false;
  }
}

function verifyHealth() {
  const response = http.get(`${baseUrl}/actuator/health`, {
    timeout: '5s', redirects: 0, tags: { name: 'health' },
  });
  let healthy = false;
  try { healthy = response.status === 200 && response.json('status') === 'UP'; } catch { /* invalid response */ }
  if (!healthy) exec.test.abort(`헬스 체크 실패: HTTP ${response.status}`);
}

export function setup() {
  verifyHealth();
  const response = http.get(`${baseUrl}/api/v1/works`, params);
  if (!validWorks(response)) exec.test.abort(`사전 조회 실패: HTTP ${response.status}. 토큰과 작품 등록 여부를 확인하세요.`);
  console.log(`사전 조회 성공: 작품 ${response.json('data').length}개, 단계 ${stage}`);
}

export default function () {
  const response = http.get(`${baseUrl}/api/v1/works`, params);
  const valid = check(response, { '작품 목록 정상 응답': validWorks });
  duration.add(response.timings.duration);
  failures.add(!valid);
  if (!valid) exec.test.abort(`작품 조회 실패로 중단: HTTP ${response.status}`);
  if (__VU === 1 && __ITER % 10 === 0) verifyHealth();
  sleep(1);
}

export function teardown() { verifyHealth(); }

export function handleSummary(data) {
  const finishedAt = new Date();
  const runDurationMs = data.state.testRunDurationMs;
  const failureValues = data.metrics.works_failures?.values;
  const report = {
    approximateStartedAt: new Date(finishedAt.getTime() - runDurationMs).toISOString(),
    finishedAt: finishedAt.toISOString(),
    target: `${baseUrl}/api/v1/works`,
    stage,
    configuredLoad: stages[stage],
    scope: '같은 테스트 계정의 작품 목록 반복 조회; 네트워크 영향을 포함한 단기 측정',
    durationMs: data.metrics.works_duration_ms?.values || null,
    failures: failureValues ? {
      rate: failureValues.rate,
      failedRequests: failureValues.passes,
      successfulRequests: failureValues.fails,
    } : null,
    checks: data.metrics.checks?.values || null,
    thresholds: {
      duration: data.metrics.works_duration_ms?.thresholds || null,
      failures: data.metrics.works_failures?.thresholds || null,
    },
    iterations: data.metrics.iterations?.values || null,
    httpRequestsIncludingHealthAndPreflight: data.metrics.http_reqs?.values || null,
    k6RunDurationMs: runDurationMs,
  };
  const output = `${JSON.stringify(report, null, 2)}\n`;
  return { stdout: output, [__ENV.RESULT_FILE || `summary-${stage}.json`]: output };
}
