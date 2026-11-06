#!/usr/bin/env bash
# Harness feedback in one screen: score, every check's status, what changed since the last run,
# and for each non-OK check the latest timeline entry (failed request + transcript).
# Snapshots in .local/feedback/. Never prints the token.
#
# Usage: workflow/bin/feedback.sh            summary + details of non-OK checks
#        workflow/bin/feedback.sh --raw      the raw feedback JSON
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
[[ -s .fagdag/harness-token ]] || { echo "no .fagdag/harness-token, paste it from the harness /feedback page" >&2; exit 1; }
H=$(cat .fagdag/harness-url)
TEAM=$(cat .fagdag/team)
dir=.local/feedback
mkdir -p $dir

api() { # rate limit: bursts of 5, then 1/s; retry once on 429
    local out code
    out=$(curl -sS -w '\n%{http_code}' -H "Authorization: Bearer $(cat .fagdag/harness-token)" "$H$1")
    code=${out##*$'\n'}
    if [[ $code == 429 ]]; then sleep 2; api "$1"; return; fi
    [[ $code == 200 ]] || { echo "harness $1 -> $code" >&2; return 1; }
    printf '%s' "${out%$'\n'*}"
}

[[ -f $dir/latest.json ]] && cp $dir/latest.json $dir/previous.json
api "/api/teams/$TEAM/feedback" >$dir/latest.json
cp $dir/latest.json "$dir/$(date -u +%Y%m%dT%H%M%SZ).json"

if [[ ${1:-} == --raw ]]; then jq . $dir/latest.json; exit; fi

jq -r '
  "tick \(.tick)   score \(.score.total) (earned \(.score.earned), bled \(.score.bled))   down: \(.down.current)\(if .down.reason then " (\(.down.reason))" else "" end)",
  "",
  (.features[] | "\(.slug)\(if .claimed then " [claimed @\(.claimedAtTick)]" else "" end): "
     + ([(.checks // [])[] | "\(.name | sub("check-"; "#"))=\(.status)"] | join(" ")))
' $dir/latest.json

if [[ -f $dir/previous.json ]]; then
    changes=$(jq -rn --slurpfile a $dir/previous.json --slurpfile b $dir/latest.json '
      def flat: [.features[] as $f | ($f.checks // [])[] | {key: "\($f.slug)/\(.name)", value: .status}] | from_entries;
      ($a[0] | flat) as $old | ($b[0] | flat) as $new
      | $new | to_entries[] | select($old[.key] != .value)
      | "  \(.key): \($old[.key] // "new") -> \(.value)"')
    echo
    if [[ -n $changes ]]; then echo "changed since last run:"; echo "$changes"; else echo "no changes since last run"; fi
fi

bad=$(jq -r '.features[] as $f | ($f.checks // [])[] | select(.status != "OK" and .status != null) | "\($f.slug)/\(.name)"' $dir/latest.json)
for check in $bad; do
    echo
    echo "=== $check"
    jq -r --arg c "$check" '.features[] as $f | ($f.checks // [])[] | select("\($f.slug)/\(.name)" == $c)
      | "status \(.status)", (.reqs[]? | "  req: \(.)"), (.notes[]? | "  note: \(.)")' $dir/latest.json
    sleep 1
    api "/api/teams/$TEAM/feedback/timeline?check=$check" >"$dir/timeline-${check//\//_}.json" || continue
    jq '.[-1] // "no timeline entries"' "$dir/timeline-${check//\//_}.json" | head -c 4000
    echo
done
