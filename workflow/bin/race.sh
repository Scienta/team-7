#!/usr/bin/env bash
# Concurrency checks Hurl can't express: parallel requests against one speaker or talk.
#
# Usage: workflow/bin/race.sh [base-url]
set -uo pipefail
base="${1:-http://localhost:8080}"
fail=0
run=$RANDOM$RANDOM

post() { curl -sS -H 'Content-Type: application/json' -X POST "$base$1" -d "$2"; }
put() { curl -sS -o /dev/null -w '%{http_code}\n' -H 'Content-Type: application/json' -X PUT "$base$1" -d "$2"; }
id() { python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])'; }
field() { python3 -c "import json,sys; print(json.load(sys.stdin)['$1'])"; }
expect() { # name expected actual
    if [[ "$2" == "$3" ]]; then echo "ok    $1"; else echo "FAIL  $1: expected $2, got $3"; fail=1; fi
}

# Talks: at most 3 per speaker, also when they arrive at once.
sid=$(post /speakers "{\"name\":\"Race $run\",\"email\":\"race-$run@example.com\"}" | id)
codes=$(for i in $(seq 1 10); do
    curl -sS -o /dev/null -w '%{http_code}\n' -H 'Content-Type: application/json' -X POST "$base/talks" \
        -d "{\"title\":\"Race $i\",\"format\":\"WORKSHOP\",\"level\":\"BEGINNER\",\"speakerId\":$sid}" &
done; wait)
expect "talks: 10 parallel submissions -> 3 created" 3 "$(grep -c '^201$' <<<"$codes")"
expect "talks: the rest rejected with 4xx" 7 "$(grep -c '^4' <<<"$codes")"
expect "talks: speaker has 3 talks" 3 "$(curl -sS "$base/speakers/$sid/talks" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))')"

# Ratings: many reviewers at once, each double-clicking.
sid=$(post /speakers "{\"name\":\"Rater $run\",\"email\":\"rater-$run@example.com\"}" | id)
tid=$(post /talks "{\"title\":\"Busy\",\"format\":\"WORKSHOP\",\"level\":\"BEGINNER\",\"speakerId\":$sid}" | id)
codes=$(for r in $(seq 1 20); do
    put "/talks/$tid/ratings/$r" '{"score":4}' &
    put "/talks/$tid/ratings/$r" '{"score":4}' &
done; wait)
expect "ratings: 40 parallel PUTs all 200" 40 "$(grep -c '^200$' <<<"$codes")"
talk=$(curl -sS "$base/talks/$tid")
expect "ratings: 20 reviewers x 2 clicks -> ratingCount 20" 20 "$(field ratingCount <<<"$talk")"
expect "ratings: averageRating 4.0" 4.0 "$(field averageRating <<<"$talk")"

# Ratings: one reviewer changing their mind rapidly ends with one rating.
tid=$(post /talks "{\"title\":\"Fickle\",\"format\":\"WORKSHOP\",\"level\":\"BEGINNER\",\"speakerId\":$sid}" | id)
for s in 1 2 3 4 5 1 2 3 4 5; do put "/talks/$tid/ratings/1" "{\"score\":$s}" >/dev/null & done; wait
expect "ratings: 10 parallel re-rates by one reviewer -> ratingCount 1" 1 "$(curl -sS "$base/talks/$tid" | field ratingCount)"

exit $fail
