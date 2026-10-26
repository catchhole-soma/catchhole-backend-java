import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import { createHash } from 'node:crypto';

const options = Object.fromEntries(process.argv.slice(2).reduce((rows, value, index, all) => value.startsWith('--') ? [...rows, [value.slice(2), all[index + 1]]] : rows, []));
const credentials = JSON.parse(await readFile(options.credentials, 'utf8'));
if (credentials.api !== 'http://127.0.0.1:29480' || credentials.frontend !== 'http://127.0.0.1:3440') throw Error('Dedicated localhost ports required');
const { chromium } = await import(pathToFileURL(`${options.root}/worktrees/frontend/node_modules/playwright-core/index.mjs`).href);
await mkdir(options.output, { recursive: true });
const login = await fetch(credentials.api + '/api/v1/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email: credentials.email, password: credentials.password }) });
if (!login.ok) throw Error('Synthetic login failed');
const token = (await login.json()).data.accessToken;
const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({ viewport: { width: 1280, height: 800 }, serviceWorkers: 'block' });
const blockedExternalOrigins = new Set();
await context.route('**/*', route => {
  const origin = new URL(route.request().url()).origin;
  if ([credentials.api, credentials.frontend].includes(origin)) return route.continue();
  blockedExternalOrigins.add(origin);
  return route.abort('blockedbyclient');
});
await context.addInitScript(({ token }) => localStorage.setItem('accessToken', token), { token });
await context.addInitScript(() => {
  window.__candidateReady = null;
  let armed = false;
  const check = () => document.querySelectorAll('.candidate-group-card').length === 9
    && !document.body.innerText.includes('설정 후보를 불러오고 있습니다.');
  function tick() {
    if (window.__candidateReady) return;
    if (check() && !armed) {
      armed = true;
      requestAnimationFrame(() => requestAnimationFrame(() => {
        if (check()) window.__candidateReady = { pageReadyMs: performance.now(), timeOriginEpochMs: performance.timeOrigin, groups: 9 };
        else armed = false;
      }));
    }
    requestAnimationFrame(tick);
  }
  requestAnimationFrame(tick);
});
const page = await context.newPage();
const cdp = await context.newCDPSession(page);
await cdp.send('Network.enable');
await cdp.send('Network.setCacheDisabled', { cacheDisabled: true });
const events = new Map(), collectors = new Set(), errors = [];
let fatalApiError = null;
context.on('request', request => events.set(request, { url: request.url(), method: request.method(), resourceType: request.resourceType(), startedEpochMs: Date.now(), finished: false }));
context.on('requestfinished', request => {
  const collecting = (async () => {
    const row = events.get(request);
    if (!row) return;
    const response = await request.response();
    await response?.finished();
    Object.assign(row, { finished: true, status: response?.status(), timing: request.timing(), sizes: await request.sizes() });
    if (row.url.startsWith(credentials.api + '/api/')) {
      const body = await response.body();
      row.decodedBodyBytes = body.length;
      row.bodySha256 = createHash('sha256').update(body).digest('hex');
      if (row.status >= 500) fatalApiError = `HTTP ${row.status}`;
    }
  })().catch(error => errors.push(String(error)));
  collectors.add(collecting);
  collecting.finally(() => collectors.delete(collecting));
});
context.on('requestfailed', request => {
  const row = events.get(request);
  if (row) Object.assign(row, { finished: true, failed: true, failure: request.failure()?.errorText, timing: request.timing() });
  if (request.url().startsWith(credentials.api + '/api/')) fatalApiError = request.failure()?.errorText;
});
page.on('pageerror', error => errors.push(String(error)));
const result = { phase: options.phase, browserVersion: browser.version(), protocol: 'one fresh browser context; HTTP cache disabled; API requests starting through 500ms after character-ready, drained up to30s; stop on first API failure; external origins blocked identically', success: false };
try {
  await page.goto(credentials.frontend + `/setting-review?workId=${credentials.workId}&batchId=${credentials.batchId}&jobType=SETTING_EXTRACTION`, { waitUntil: 'commit', timeout: 45000 });
  const deadline = Date.now() + 45000;
  let ready = null;
  while (Date.now() < deadline && !fatalApiError) {
    ready = await page.evaluate(() => window.__candidateReady);
    if (ready && await page.evaluate(cutoff => performance.now() >= cutoff, ready.pageReadyMs + 500)) break;
    await new Promise(resolve => setTimeout(resolve, 25));
  }
  await Promise.allSettled([...collectors]);
  if (fatalApiError) throw Error('Stopped on first API failure: ' + fatalApiError);
  if (!ready) throw Error('Character groups did not become ready within 45s');
  Object.assign(result, ready);
  result.observationCutoffEpochMs = ready.timeOriginEpochMs + ready.pageReadyMs + 500;
  const drainDeadline = Date.now() + 30000;
  while (Date.now() < drainDeadline && [...events.values()].some(row => row.url.startsWith(credentials.api + '/api/') && row.startedEpochMs <= result.observationCutoffEpochMs && !row.finished)) {
    if (fatalApiError) throw Error('Stopped on first API failure: ' + fatalApiError);
    await new Promise(resolve => setTimeout(resolve, 25));
  }
  await Promise.allSettled([...collectors]);
  result.loadEventEndMs = await page.evaluate(() => performance.getEntriesByType('navigation')[0]?.loadEventEnd);
  result.success = true;
  await writeFile(`${options.output}/visible-text.txt`, await page.locator('body').innerText());
} catch (error) {
  result.error = String(error).split('\n')[0];
}
await context.close();
await Promise.allSettled([...collectors]);
result.requests = [...events.values()].filter(row => row.url.startsWith(credentials.api + '/api/') && (!result.observationCutoffEpochMs || row.startedEpochMs <= result.observationCutoffEpochMs));
result.apiRequestCount = result.requests.length;
result.apiResponseBytes = result.requests.reduce((sum, row) => sum + (row.decodedBodyBytes ?? 0), 0);
result.failedApiRequests = result.requests.filter(row => row.failed || row.status >= 400 || !row.finished).length;
result.externalOriginsBlocked = [...blockedExternalOrigins].sort();
result.collectorErrors = errors;
result.success = result.success && !result.failedApiRequests && !errors.length;
await writeFile(`${options.output}/summary.json`, JSON.stringify(result, null, 2) + '\n');
await browser.close();
console.log(JSON.stringify({ output: options.output, success: result.success, apiRequestCount: result.apiRequestCount, apiResponseBytes: result.apiResponseBytes, pageReadyMs: result.pageReadyMs, error: result.error }));
