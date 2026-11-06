// k6 load test (PLAN Phase 12): 100 requests/s on the borrower journey and staff dashboard reads,
// p95 < 400 ms (target: RDS db.t4g.medium). Run against STAGING only, never prod.
//
//   k6 run tools/k6/journey.js \
//     -e BASE_URL=https://api.staging.dhannow.com \
//     -e BORROWER_TOKEN=<borrower access JWT> -e STAFF_TOKEN=<staff JWT, a dashboard role> \
//     [-e APPLICATION_ID=<id>] [-e LOAN_ID=<id>] [-e RATE=100] [-e DURATION=5m]
//
// Tokens are taken from a staging sign-in (the OTP and staff login limiters would throttle a test
// that signs in per iteration). Nothing is written: every request is a GET.
import http from 'k6/http';
import { check, group } from 'k6';

const BASE = (__ENV.BASE_URL || '').replace(/\/$/, '');
const RATE = Number(__ENV.RATE || 100);
const DURATION = __ENV.DURATION || '5m';
const borrower = __ENV.BORROWER_TOKEN;
const staff = __ENV.STAFF_TOKEN;
const appId = __ENV.APPLICATION_ID;
const loanId = __ENV.LOAN_ID;

if (!BASE || BASE.includes('api.dhannow.com')) {
  throw new Error('Set BASE_URL to a staging API (prod is refused).');
}

export const options = {
  scenarios: {
    journey: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(20, RATE),
      maxVUs: RATE * 4,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<400'],
    'http_req_duration{group:::borrower}': ['p(95)<400'],
    'http_req_duration{group:::staff}': ['p(95)<400'],
    checks: ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const headers = (token, app = true) => ({
  headers: { Authorization: `Bearer ${token}`, Accept: 'application/json', ...(app ? { 'X-Client': 'android' } : {}) },
});

const ok = (res) => check(res, { '2xx': (r) => r.status >= 200 && r.status < 300 });

// Borrower home: what the app reads on launch and on resume (Phase 11 api layer).
const borrowerCalls = [
  () => http.get(`${BASE}/api/app/config`, { tags: { name: 'app/config' } }),
  () => http.get(`${BASE}/api/applications/mine`, { ...headers(borrower), tags: { name: 'applications/mine' } }),
  () => http.get(`${BASE}/api/me/credit-level`, { ...headers(borrower), tags: { name: 'me/credit-level' } }),
  () => http.get(`${BASE}/api/notifications`, { ...headers(borrower), tags: { name: 'notifications' } }),
  ...(appId ? [() => http.get(`${BASE}/api/applications/${appId}/journey`, { ...headers(borrower), tags: { name: 'journey' } })] : []),
  ...(loanId ? [
    () => http.get(`${BASE}/api/loan/${loanId}`, { ...headers(borrower), tags: { name: 'loan' } }),
    () => http.get(`${BASE}/api/loan/${loanId}/outstanding`, { ...headers(borrower), tags: { name: 'loan/outstanding' } }),
  ] : []),
];

// Staff dashboard reads.
const staffCalls = [
  () => http.get(`${BASE}/api/dashboard/snapshot`, { ...headers(staff, false), tags: { name: 'dashboard/snapshot' } }),
  () => http.get(`${BASE}/api/dashboard/daily`, { ...headers(staff, false), tags: { name: 'dashboard/daily' } }),
  () => http.get(`${BASE}/api/dashboard/role-view`, { ...headers(staff, false), tags: { name: 'dashboard/role-view' } }),
];

export function setup() {
  if (!borrower || !staff) throw new Error('Set BORROWER_TOKEN and STAFF_TOKEN.');
}

// 70% borrower, 30% staff traffic.
export default function () {
  if (Math.random() < 0.7) {
    group('borrower', () => ok(borrowerCalls[Math.floor(Math.random() * borrowerCalls.length)]()));
  } else {
    group('staff', () => ok(staffCalls[Math.floor(Math.random() * staffCalls.length)]()));
  }
}
