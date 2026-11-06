#!/usr/bin/env bash
# Our own gate: the published examples, our edge-case Hurl suites, replays of the harness's own transcripts
# (workflow/hurl/harness/, from transcripts.py), the consistency matrix (consistency.py) and the concurrency checks.
# Each suite file covers one rule, so one failure doesn't hide the others.
#
# Usage: workflow/bin/check.sh [base-url] [hurl files...]
#        base-url defaults to http://localhost:8080; pass "$TEAM_URL" to check the deployed service.
#        With files, runs only those (no examples, no race checks).
# HTML report: .local/hurl-report/index.html
set -uo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
base="${1:-http://localhost:8080}"
shift || true

if (($# > 0)); then
    files=("$@")
    race=false
else
    python3 workflow/bin/transcripts.py >/dev/null 2>&1 || true # pick up new harness transcripts
    files=(examples/*.hurl workflow/hurl/*.hurl workflow/hurl/harness/*.hurl)
    race=true
fi

rc=0
hurl --test --jobs 4 --variable "base_url=$base" --variable "run=$(date +%s)$RANDOM" \
    --report-html .local/hurl-report "${files[@]}" || rc=1
if $race; then
    echo
    python3 workflow/bin/consistency.py "$base" | tail -1 || rc=1
    [[ ${PIPESTATUS[0]} == 0 ]] || { python3 workflow/bin/consistency.py "$base" | grep FAIL | head -20; rc=1; }
    echo
    workflow/bin/race.sh "$base" || rc=1
fi
echo
((rc == 0)) && echo "ALL GREEN against $base" || echo "FAILURES against $base"
exit $rc
