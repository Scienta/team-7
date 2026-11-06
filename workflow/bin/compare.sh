#!/usr/bin/env bash
# Behaviour-equivalence check for refactors: runs <ref> (default HEAD) on :8082 beside the working tree on :8080,
# both in Docker against the same local database, sends both the same reads and rejected writes (no side effects),
# and diffs status + body (timestamps stripped). Exit 1 on any difference.
#
# Usage: workflow/bin/compare.sh [ref]            builds/starts <ref> on :8082 (workflow/bin/local.sh ref)
#        workflow/bin/compare.sh --running [ref]  both already running, just diff
#        Start the working tree first: workflow/bin/local.sh up
set -uo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
running=false
[[ ${1:-} == --running ]] && { running=true; shift; }
ref=$(git rev-parse --short "${1:-HEAD}")
new=http://localhost:8080
old=http://localhost:8082

curl -fsS "$new/health" >/dev/null || { echo "no app on :8080, run workflow/bin/local.sh up" >&2; exit 1; }
$running || workflow/bin/local.sh ref "$ref" >/dev/null || { echo "can't run $ref on :8082" >&2; exit 1; }
curl -fsS "$old/health" >/dev/null || { echo "no app on :8082" >&2; exit 1; }

q() { docker compose exec -T postgres psql -U cfp -tAc "$1"; }
s=$(q "SELECT MIN(speaker_id) FROM talk WHERE title = 'Seed talk'")
t=$(q "SELECT MIN(talk_id) FROM rating")
u=$(q "SELECT MIN(t.id) FROM talk t WHERE NOT EXISTS (SELECT 1 FROM rating r WHERE r.talk_id = t.id)")

# method path [json-body]
requests=(
    "GET /talks"
    "GET /talks?sort=rating"
    "GET /talks?speakerId=$s"
    "GET /talks?speakerId=999999999"
    "GET /talks?sort=bogus"
    "GET /talks/$t"
    "GET /talks/$u"
    "GET /talks/999999999"
    "GET /talks/abc"
    "GET /speakers/$s"
    "GET /speakers/$s/"
    "GET /speakers/$s/talks"
    "GET /speakers/999999999"
    "GET /speakers/999999999/talks"
    "GET /speakers/abc"
    "GET /speakers/search?q=Seed%201"
    "GET /admin/stats"
    "GET /admin/talks.csv"
    'POST /speakers {}'
    'POST /speakers {"name":"x","email":"not-an-email"}'
    'POST /speakers {"name":"  ","email":"blank@example.com"}'
    'POST /speakers {'
    'POST /talks {"title":"x","format":"KEYNOTE","level":"BEGINNER","speakerId":'"$s"'}'
    'POST /talks {"title":"x","format":"WORKSHOP","level":"EXPERT","speakerId":'"$s"'}'
    'POST /talks {"title":" ","format":"WORKSHOP","level":"BEGINNER","speakerId":'"$s"'}'
    'POST /talks {"title":"x","format":"WORKSHOP","level":"BEGINNER","speakerId":999999999}'
    "PUT /talks/$t/ratings/0 {\"score\":3}"
    "PUT /talks/$t/ratings/1 {\"score\":9}"
    "PUT /talks/$t/ratings/1 {\"score\":\"4\"}"
    "PUT /talks/$t/ratings/1 {}"
    'PUT /talks/999999999/ratings/1 {"score":3}'
)

# status line + body; JSON bodies normalised and stripped of the fields that differ per call
fetch() {
    local base=$1 method=$2 path=$3 body=${4:-}
    local args=(-sS -X "$method" -w '\n%{http_code}' "$base$path")
    [[ -n $body ]] && args+=(-H 'Content-Type: application/json' -d "$body")
    local out code
    out=$(curl "${args[@]}")
    code=${out##*$'\n'}
    out=${out%$'\n'*}
    echo "HTTP $code"
    # Validation errors come in random order; everything else keeps its order (list order is behaviour).
    jq -S 'walk(if type == "object" then del(.timestamp, .generated) | (if .errors | type == "array" then .errors |= sort else . end) else . end)' \
        <<<"$out" 2>/dev/null || echo "$out"
}

diffs=0
for r in "${requests[@]}"; do
    read -r method path body <<<"$r"
    if d=$(diff <(fetch "$old" "$method" "$path" "$body") <(fetch "$new" "$method" "$path" "$body")); then
        echo "same  $method $path"
    else
        echo "DIFF  $method $path ${body:+$body}"
        sed 's/^/      /' <<<"$d" | head -20
        diffs=$((diffs + 1))
    fi
done
echo
if ((diffs == 0)); then echo "EQUIVALENT to $ref (${#requests[@]} requests)"; else echo "$diffs of ${#requests[@]} requests differ from $ref"; fi
((diffs == 0))
