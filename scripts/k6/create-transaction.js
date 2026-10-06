// Create-transaction load test (docs/performance.md). Run through scripts/load-test.sh, which owns the stack, the
// outbox sampling and the two invocations:
//   MODE=seed  creates the accounts, funds each with one IN, and writes their IDs to $OUT_DIR/accounts.json.
//   MODE=load  reads those IDs, then a warm-up and a measured window of POST /accounts/{id}/transactions.
// Env: BASE_URL (http://app:8080), SCENARIO spread|hot, VUS (50), WARMUP (20s), DURATION (60s), OUT_DIR (/out),
//      RESULT (the load summary's file name). Only the `measure` scenario's numbers are reported.
import http from 'k6/http';
import { check, fail } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://app:8080';
const MODE = __ENV.MODE || 'load';
const SCENARIO = __ENV.SCENARIO || 'spread';
const VUS = parseInt(__ENV.VUS || '50', 10);
const WARMUP = __ENV.WARMUP || '20s';
const DURATION = __ENV.DURATION || '60s';
const OUT_DIR = __ENV.OUT_DIR || '/out';
const ACCOUNTS = SCENARIO === 'hot' ? 1 : 1000;
// Enough for every OUT the run could make on one account; well inside NUMERIC(19,2).
const SEED_AMOUNT = 100000000;   // a JSON number, as clients send it
const JSON_HEADERS = { headers: { 'Content-Type': 'application/json' } };

// open() works in the init context only, and the file exists only for the load invocation.
const accountIds = MODE === 'load' ? JSON.parse(open(`${OUT_DIR}/accounts.json`)) : [];

export const options = MODE === 'seed'
    ? { vus: 1, iterations: 1, setupTimeout: '5m', summaryTrendStats: ['avg', 'p(95)'] }
    : {
        scenarios: {
            warmup: { executor: 'constant-vus', vus: VUS, duration: WARMUP, gracefulStop: '0s' },
            measure: { executor: 'constant-vus', vus: VUS, duration: DURATION, startTime: WARMUP },
        },
        // Tagged thresholds make k6 keep the measure-only sub-metrics; the 201 rate also fails a broken run.
        thresholds: {
            'http_req_duration{scenario:measure}': ['p(95)>=0'],
            'http_reqs{scenario:measure}': ['count>0'],
            'checks{scenario:measure}': ['rate>0.999'],
        },
        summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
    };

// A k6 duration ("90s", "2m", "1m30s", "500ms") in seconds: TPS divides by it, so "1m" must not read as 1.
function toSeconds(duration) {
    const units = { h: 3600, m: 60, s: 1, ms: 0.001 };
    let seconds = 0;
    const rest = duration.replace(/(\d+(?:\.\d+)?)(ms|h|m|s)/g, (_, n, unit) => {
        seconds += parseFloat(n) * units[unit];
        return '';
    });
    if (rest !== '' || seconds <= 0) {
        throw new Error(`DURATION must be a k6 duration such as 60s or 2m, got "${duration}"`);
    }
    return seconds;
}

function post(path, body) {
    return http.post(`${BASE_URL}${path}`, JSON.stringify(body), JSON_HEADERS);
}

// Seed mode: setup() does the work and its return value reaches handleSummary as setup_data.
export function setup() {
    if (MODE !== 'seed') {
        return {};
    }
    const ids = [];
    for (let i = 0; i < ACCOUNTS; i++) {
        const account = post('/accounts', { customerId: `perf-${SCENARIO}-${i}`, country: 'EE', currencies: ['EUR'] });
        if (account.status !== 201) {
            fail(`create account: ${account.status} ${account.body}`);
        }
        const id = account.json('accountId');
        const fund = post(`/accounts/${id}/transactions`,
            { amount: SEED_AMOUNT, currency: 'EUR', direction: 'IN', description: 'seed' });
        if (fund.status !== 201) {
            fail(`fund account: ${fund.status} ${fund.body}`);
        }
        ids.push(id);
    }
    return { ids };
}

export default function () {
    if (MODE === 'seed') {
        return;
    }
    // Alternate IN and OUT per VU; OUT never lacks funds thanks to the seed, so every response should be 201.
    const id = accountIds[Math.floor(Math.random() * accountIds.length)];
    const direction = __ITER % 2 === 0 ? 'IN' : 'OUT';
    const res = post(`/accounts/${id}/transactions`,
        { amount: 1, currency: 'EUR', direction, description: 'load' });
    check(res, { 'status is 201': (r) => r.status === 201 });
}

export function handleSummary(data) {
    if (MODE === 'seed') {
        return { [`${OUT_DIR}/accounts.json`]: JSON.stringify(data.setup_data.ids) };
    }
    const m = data.metrics;
    const duration = m['http_req_duration{scenario:measure}'].values;
    const reqs = m['http_reqs{scenario:measure}'].values;
    const checks = m['checks{scenario:measure}'].values;
    const seconds = toSeconds(DURATION);
    const result = {
        scenario: SCENARIO,
        vus: VUS,
        durationSeconds: seconds,
        requests: reqs.count,
        tps: reqs.count / seconds,
        okRate: checks.rate,
        latencyMs: { avg: duration.avg, p50: duration['p(50)'], p95: duration['p(95)'], p99: duration['p(99)'],
            max: duration.max },
    };
    return {
        [`${OUT_DIR}/${__ENV.RESULT || 'result.json'}`]: JSON.stringify(result, null, 2),
        stdout: `${SCENARIO}: ${result.tps.toFixed(1)} TPS, p95 ${result.latencyMs.p95.toFixed(1)} ms, `
            + `201s ${(100 * result.okRate).toFixed(2)}%\n`,
    };
}
