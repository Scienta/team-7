// Mixed concurrent load: committee reading and ranking, reviewers rating, speakers registering and submitting.
// Measures p95 per endpoint, fails on any unexpected status. Run through workflow/bin/perf.sh.
//
// Env: BASE_URL (default http://localhost:8080), DURATION (default 30s), VUS (total concurrency, default 60;
// the platform answers 503 itself beyond 80), SUMMARY (path for the JSON summary).
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const DURATION = __ENV.DURATION || '30s';
const VUS = parseInt(__ENV.VUS || '60', 10);
const JSON_HEADERS = { 'Content-Type': 'application/json' };

// Every endpoint we measure gets its own p95 line in the summary (k6 only reports tagged sub-metrics that have a
// threshold). The budgets are generous ceilings; regressions are caught by perf.sh against the baseline.
const ENDPOINTS = [
    'GET /talks', 'GET /talks?sort=rating', 'GET /talks?speakerId', 'GET /talks/{id}', 'GET /speakers/{id}',
    'GET /speakers/{id}/talks', 'POST /speakers', 'POST /talks', 'PUT /talks/{id}/ratings/{reviewerId}',
];
const thresholds = { checks: ['rate==1'], http_req_failed: ['rate==0'] };
for (const name of ENDPOINTS) {
    thresholds[`http_req_duration{name:${name}}`] = ['p(95)<2000'];
}

export const options = {
    thresholds,
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
    scenarios: {
        committee: { executor: 'constant-vus', exec: 'committee', vus: Math.max(1, Math.round(VUS * 0.4)), duration: DURATION },
        reviewers: { executor: 'constant-vus', exec: 'reviewers', vus: Math.max(1, Math.round(VUS * 0.4)), duration: DURATION },
        speakers: { executor: 'constant-vus', exec: 'speakers', vus: Math.max(1, Math.round(VUS * 0.2)), duration: DURATION },
    },
};

// 409 is the expected answer for the fourth talk; anything else outside 2xx counts as failed.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 409));

function send(method, path, body, name) {
    return http.request(method, `${BASE}${path}`, body === null ? null : JSON.stringify(body),
        { headers: JSON_HEADERS, tags: { name } });
}

function pick(a) {
    return a[Math.floor(Math.random() * a.length)];
}

function uid() {
    return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

export function setup() {
    const speakers = [];
    const talks = [];
    for (let i = 0; i < 20; i++) {
        const s = send('POST', '/speakers', { name: `Load ${i}`, email: `load-${uid()}@example.com` }, 'POST /speakers').json();
        speakers.push(s.id);
        for (const format of ['PRESENTATION', 'WORKSHOP']) {
            const t = send('POST', '/talks', { title: `Load ${i} ${format}`, format, level: 'BEGINNER', speakerId: s.id }, 'POST /talks');
            talks.push(t.json().id);
        }
    }
    return { speakers, talks };
}

export function committee(data) {
    const r = Math.random();
    let res;
    if (r < 0.25) {
        res = send('GET', '/talks?sort=rating', null, 'GET /talks?sort=rating');
    } else if (r < 0.40) {
        res = send('GET', '/talks', null, 'GET /talks');
    } else if (r < 0.55) {
        res = send('GET', `/talks?speakerId=${pick(data.speakers)}`, null, 'GET /talks?speakerId');
    } else if (r < 0.80) {
        res = send('GET', `/talks/${pick(data.talks)}`, null, 'GET /talks/{id}');
    } else if (r < 0.90) {
        res = send('GET', `/speakers/${pick(data.speakers)}`, null, 'GET /speakers/{id}');
    } else {
        res = send('GET', `/speakers/${pick(data.speakers)}/talks`, null, 'GET /speakers/{id}/talks');
    }
    check(res, { 'read 200': (x) => x.status === 200 });
}

export function reviewers(data) {
    // A small pool of talks and reviewers, so the same rows are hit at once (double clicks included).
    const talk = pick(data.talks);
    const reviewer = 1 + Math.floor(Math.random() * 50);
    const res = send('PUT', `/talks/${talk}/ratings/${reviewer}`, { score: 1 + Math.floor(Math.random() * 5) },
        'PUT /talks/{id}/ratings/{reviewerId}');
    check(res, { 'rate 200': (x) => x.status === 200 });
}

export function speakers() {
    const s = send('POST', '/speakers', { name: 'Speaker', email: `spk-${uid()}@example.com` }, 'POST /speakers');
    if (!check(s, { 'register 201': (x) => x.status === 201 })) {
        return;
    }
    const id = s.json().id;
    // Four at once: exactly three may land.
    const params = { headers: JSON_HEADERS, tags: { name: 'POST /talks' } };
    const res = http.batch([1, 2, 3, 4].map((n) => ['POST', `${BASE}/talks`,
        JSON.stringify({ title: `Talk ${n}`, format: 'LIGHTNING_20', level: 'ADVANCED', speakerId: id }), params]));
    check(res, {
        'submit: exactly 3 created': (r) => r.filter((x) => x.status === 201).length === 3,
        'submit: the 4th is 409': (r) => r.filter((x) => x.status === 409).length === 1,
    });
}

export function teardown(data) {
    // Invariants after the storm: nobody above the limit, every talk's rating summary consistent.
    for (const id of data.speakers) {
        const talks = send('GET', `/speakers/${id}/talks`, null, 'GET /speakers/{id}/talks').json();
        check(talks, { 'teardown: at most 3 talks per speaker': (t) => t.length <= 3 });
        for (const t of talks) {
            check(t, {
                'teardown: ratingCount within the reviewer pool': (x) => x.ratingCount >= 0 && x.ratingCount <= 50,
                'teardown: average null iff unrated': (x) => (x.ratingCount === 0) === (x.averageRating === null),
            });
        }
    }
}

export function handleSummary(data) {
    const out = { stdout: '' };
    if (__ENV.SUMMARY) {
        out[__ENV.SUMMARY] = JSON.stringify(data, null, 2);
    }
    return out;
}
