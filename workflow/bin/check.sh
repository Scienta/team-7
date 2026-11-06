#!/usr/bin/env bash
# Our own gate: the published examples, our edge-case Hurl suites and the concurrency checks.
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
    files=(examples/*.hurl workflow/hurl/*.hurl)
    race=true
fi

rc=0
hurl --test --jobs 4 --variable "base_url=$base" --report-html .local/hurl-report "${files[@]}" || rc=1
if $race; then
    echo
    workflow/bin/race.sh "$base" || rc=1
fi
echo
((rc == 0)) && echo "ALL GREEN against $base" || echo "FAILURES against $base"
exit $rc
