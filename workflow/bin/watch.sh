#!/usr/bin/env bash
# Polls the harness and exits as soon as something changes, printing what: a check's status, a newly released
# or claimed feature, the service going down or coming back. Meant to run in the background, so whoever started it
# (a person or an agent) gets woken up by the exit and restarts it after looking.
#
# Usage: workflow/bin/watch.sh [interval-seconds]     (default 120, minimum 60: the harness API is rate-limited)
set -uo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
interval=$((${1:-120} < 60 ? 60 : ${1:-120}))
H=$(cat .fagdag/harness-url)
TEAM=$(cat .fagdag/team)
mkdir -p .local/feedback

# One line per thing we care about, so a plain diff shows the change.
state() {
    curl -fsS -H "Authorization: Bearer $(cat .fagdag/harness-token)" "$H/api/teams/$TEAM/feedback" | jq -r '
      "down \(.down.current)\(if .down.reason then " (\(.down.reason))" else "" end)",
      (.features[] | "feature \(.slug) \(if .claimed then "claimed" else "unclaimed" end)"),
      (.features[] as $f | ($f.checks // [])[] | "check \($f.slug)/\(.name) \(.status)")'
}

fails=0
until before=$(state); do
    fails=$((fails + 1))
    ((fails >= 5)) && { echo "harness unreachable 5 times in a row"; exit 1; }
    sleep "$interval"
done
fails=0
# Already broken when we start: say so now, don't wait for a change.
# null = not run yet (just claimed), not a failure
problems=$(grep -E '^(check |down true)' <<<"$before" | grep -vE ' (OK|null)$' || true)
if [[ -n $problems ]]; then
    echo "harness already reports problems at $(date -u +%H:%M:%SZ):"
    sed 's/^/  /' <<<"$problems"
    exit 0
fi
echo "watching from $(date -u +%H:%M:%SZ), every ${interval}s" >&2
while true; do
    sleep "$interval"
    if ! now=$(state); then
        fails=$((fails + 1))
        ((fails >= 5)) && { echo "harness unreachable 5 times in a row"; exit 1; }
        continue
    fi
    fails=0
    if [[ $now != "$before" ]]; then
        echo "harness changed at $(date -u +%H:%M:%SZ):"
        diff <(echo "$before") <(echo "$now") | grep '^[<>]' | sed 's/^</  was:/; s/^>/  now:/'
        echo "$now" >.local/feedback/watch-state.txt
        exit 0
    fi
done
