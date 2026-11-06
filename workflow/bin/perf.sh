#!/usr/bin/env bash
# Load test with regression check: runs workflow/perf/load.js (k6) against the app, prints p95 per endpoint
# next to the committed baseline, and fails on an unexpected status, a broken invariant, or a p95 regression
# (more than 25 % and 5 ms slower than workflow/perf/baseline.json).
#
# Locally it first tops the database up to production-like volume (SEED_TALKS, default 1500 talks), because list
# endpoints only show their cost with data.
#
# Usage: workflow/bin/perf.sh [base-url]                 (default http://localhost:8080)
#        workflow/bin/perf.sh --save-baseline [base-url] same, and make a green run the new baseline
# Env:   DURATION (default 30s), VUS (default 60), SEED_TALKS
set -uo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
save=false
[[ ${1:-} == --save-baseline ]] && { save=true; shift; }
base="${1:-http://localhost:8080}"
baseline=workflow/perf/baseline.json
mkdir -p .local/perf

local=false
[[ $base == http://localhost:* ]] && local=true

# The load test's own speakers (load-*, spk-* emails) and everything hanging off them, so every run starts from the
# same volume and runs stay comparable.
cleanup() {
    docker compose exec -T postgres psql -U cfp -q >/dev/null <<'EOF'
CREATE TEMP TABLE gone AS SELECT id FROM speaker WHERE email LIKE 'load-%' OR email LIKE 'spk-%';
DELETE FROM rating WHERE talk_id IN (SELECT id FROM talk WHERE speaker_id IN (SELECT id FROM gone));
DELETE FROM talk WHERE speaker_id IN (SELECT id FROM gone);
DELETE FROM speaker WHERE id IN (SELECT id FROM gone);
EOF
}

if $local; then
    cleanup
    docker compose exec -T postgres psql -U cfp -q -v want="${SEED_TALKS:-1500}" >/dev/null <<'EOF'
SELECT GREATEST(:want - COUNT(*), 0) / 2 AS speakers FROM talk \gset
-- two talks per seeded speaker, five ratings per talk
WITH s AS (
    INSERT INTO speaker (name, email)
    SELECT 'Seed ' || g, 'seed-' || g || '-' || md5(random()::text) || '@example.com' FROM generate_series(1, :speakers) g
    RETURNING id
), t AS (
    INSERT INTO talk (title, format, duration, level, status, speaker_id, submitted_at)
    SELECT 'Seed talk', 'PRESENTATION', 45, 'BEGINNER', 'SUBMITTED', s.id, (extract(epoch FROM now()) * 1000)::bigint
    FROM s, generate_series(1, 2)
    RETURNING id
)
INSERT INTO rating (talk_id, reviewer_id, score, rated_at)
SELECT t.id, r, 1 + (t.id + r) % 5, '2026-11-06 12:00:00' FROM t, generate_series(1, 5) r;
EOF
    echo "local data: $(docker compose exec -T postgres psql -U cfp -tAc 'SELECT COUNT(*) FROM talk') talks," \
        "$(docker compose exec -T postgres psql -U cfp -tAc 'SELECT COUNT(*) FROM rating') ratings"
fi

version=$(curl -fsS "$base/health" | jq -r .version) || { echo "no healthy $base/health" >&2; exit 1; }
summary=".local/perf/$(date -u +%Y%m%dT%H%M%SZ)-${version:0:20}.json"
for _ in 1 2 3 4 5; do curl -sS -o /dev/null "$base/talks?sort=rating"; done # warm up

k6 run --quiet --no-color -e BASE_URL="$base" -e SUMMARY="$summary" \
    -e DURATION="${DURATION:-30s}" -e VUS="${VUS:-60}" workflow/perf/load.js >.local/perf/k6.log 2>&1
k6rc=$?
$local && cleanup
[[ -s $summary ]] || { tail -30 .local/perf/k6.log; echo "k6 produced no summary" >&2; exit 1; }

python3 - "$summary" "$baseline" "$version" <<'EOF'
import json, os, sys
summary, baseline_path, version = sys.argv[1:]
m = json.load(open(summary))["metrics"]
prefix = "http_req_duration{name:"
now = {k[len(prefix):-1]: v["values"]["p(95)"] for k, v in m.items() if k.startswith(prefix)}
base = json.load(open(baseline_path)) if os.path.exists(baseline_path) else None
old = base["p95_ms"] if base else {}
reqs, checks = m["http_reqs"]["values"], m["checks"]["values"]
print(f"{version}: {reqs['count']} requests, {reqs['rate']:.0f}/s, failed {m['http_req_failed']['values']['rate']:.2%}, "
      f"checks {checks['passes']}/{checks['passes'] + checks['fails']}")
if base:
    print(f"baseline {base['version']}: {base['requests_per_s']:.0f}/s")
print(f"  {'endpoint':40} {'p95 ms':>8} {'baseline':>9}")
regressed = False
for name in sorted(now):
    b = old.get(name)
    flag = ""
    if b is not None and now[name] > b * 1.25 and now[name] > b + 5:
        flag, regressed = "  REGRESSED", True
    print(f"  {name:40} {now[name]:8.1f} {'' if b is None else f'{b:9.1f}'}{flag}")
json.dump({"version": version, "requests_per_s": reqs["rate"], "p95_ms": now},
          open(".local/perf/latest-p95.json", "w"), indent=2)
sys.exit(1 if regressed else 0)
EOF
cmp=$?

rc=0
((k6rc != 0)) && { echo "FAIL: k6 thresholds or checks, see .local/perf/k6.log"; grep -E '✗|crossed' .local/perf/k6.log | head -10; rc=1; }
((cmp != 0)) && { echo "FAIL: p95 regression against $baseline"; rc=1; }
if ((rc == 0)); then
    echo "PERF GREEN"
    $save && cp .local/perf/latest-p95.json "$baseline" && echo "saved as the new baseline"
fi
exit $rc
