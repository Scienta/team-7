#!/usr/bin/env bash
# Our own gate: the published examples, our edge-case Hurl suites, replays of the harness's own transcripts
# (workflow/hurl/harness/, from transcripts.py), the consistency matrix (consistency.py), the averaging rules
# (ratings_avg.py) and the concurrency checks.
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

# The programme (scheduling) is one global grid of 15 cells, and in production the harness's checks use it too:
# a cell our suite occupies, even for a moment, can make a harness check fail. So whatever writes to the
# programme runs only against a local service (scheduling-*.hurl here, and the scheduling parts of
# consistency.py and race.sh, which check the base URL themselves).
if [[ $base != http://localhost* && $base != http://127.0.0.1* ]]; then
    kept=()
    for f in "${files[@]}"; do [[ $(basename "$f") == scheduling-* ]] || kept+=("$f"); done
    files=("${kept[@]}")
    echo "(not local: skipping the scheduling suites, they would write to the shared programme)"
fi

# Harness replays that touch the programme can't own their cells the way scheduling-*.hurl do (they replay whatever
# cells the harness used), so they run one at a time after the parallel batch instead of colliding with it.
parallel=() serial=()
for f in "${files[@]}"; do
    if [[ $f == workflow/hurl/harness/* ]] && grep -q '/programme/' "$f"; then serial+=("$f"); else parallel+=("$f"); fi
done

rc=0
run="$(date +%s)$RANDOM"
hurl --test --jobs 4 --variable "base_url=$base" --variable "run=$run" \
    --report-html .local/hurl-report "${parallel[@]}" || rc=1
if ((${#serial[@]} > 0)); then
    hurl --test --jobs 1 --variable "base_url=$base" --variable "run=$run" "${serial[@]}" || rc=1
fi
if $race; then
    echo
    python3 workflow/bin/consistency.py "$base" | tail -1 || rc=1
    [[ ${PIPESTATUS[0]} == 0 ]] || { python3 workflow/bin/consistency.py "$base" | grep FAIL | head -20; rc=1; }
    echo
    # the averaging rules, original or ratings-cr (required once ratings-cr is claimed on the harness)
    python3 workflow/bin/ratings_avg.py "$base" | tail -1 || rc=1
    [[ ${PIPESTATUS[0]} == 0 ]] || { python3 workflow/bin/ratings_avg.py "$base" | grep FAIL | head -10; rc=1; }
    echo
    workflow/bin/race.sh "$base" || rc=1
fi
echo
((rc == 0)) && echo "ALL GREEN against $base" || echo "FAILURES against $base"
exit $rc
