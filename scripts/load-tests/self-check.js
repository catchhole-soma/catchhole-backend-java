import { Trend, Rate } from 'k6/metrics';
import { handleSummary as summarize } from './works.js';

export const options = { vus: 1, iterations: 1 };
const duration = new Trend('works_duration_ms', true);
const failures = new Rate('works_failures');

export default function () {
  duration.add(42);
  failures.add(false);
}

export function handleSummary(data) {
  const report = JSON.parse(summarize(data).stdout);
  if (report.durationMs['p(95)'] !== 42 || report.failures.failedRequests !== 0 || report.failures.successfulRequests !== 1) {
    throw new Error('Summary self-check failed');
  }
  if (Date.parse(report.approximateStartedAt) > Date.parse(report.finishedAt)) throw new Error('Invalid time range');
  return { stdout: 'Offline summary self-check passed; no HTTP requests sent.\n' };
}
