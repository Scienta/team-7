#!/usr/bin/env bash
# Polls the harness and origin/main and exits as soon as something changes, printing what: a new commit on main
# (a teammate pushed: check it), a check's status, a newly released or claimed feature, the service going down or
# coming back. Meant to run in the background, so whoever started it
# (a person or an agent) gets woken up by the exit and restarts it after looking.
#
# Usage: workflow/bin/watch.sh [interval-seconds]     (default 10, minimum 10: the harness API allows 1 request/s)
#        KNOWN=1 workflow/bin/watch.sh 60            don't exit on failures that are already there at start
#                                                    (they're being worked on); only report changes
set -uo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
interval=$((${1:-10} < 10 ? 10 : ${1:-10}))
H=$(cat .fagdag/harness-url)
TEAM=$(cat .fagdag/team)
mkdir -p .local/feedback

# One line per thing we care about, so a plain diff shows the change.
state() {
    curl -fsS -H "Authorization: Bearer $(cat .fagdag/harness-token)" "$H/api/teams/$TEAM/feedback" | jq -r '
      "down \(.down.current)\(if .down.reason then " (\(.down.reason))" else "" end)",
      (.features[] | "feature \(.slug) \(if .claimed then "claimed" else "unclaimed" end)"),
      (.features[] as $f | ($f.checks // [])[] | "check \($f.slug)/\(.name) \(.status)")' &&
        echo "main $(git ls-remote origin refs/heads/main | cut -c1-7)"
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
if [[ -n $problems && -z ${KNOWN:-} ]]; then
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
