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

# ---------------------------------------------------------------- helpers for the bursts below

# req METHOD PATH [BODY] [TAG]: prints "<tag> <status>"
req() {
    local code
    code=$(curl -sS -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -X "$1" "$base$2" ${3:+-d "$3"})
    echo "${4:-} $code"
}
speaker() { post /speakers "{\"name\":\"Race $1\",\"email\":\"race-$1-$run-$RANDOM@example.com\"}" | id; }
talk() { post /talks "{\"title\":\"Race\",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\",\"speakerId\":$1}" | id; }
get() { curl -sS "$base$1" | python3 -c "import json,sys; t=json.load(sys.stdin); print($2)" 2>/dev/null || echo none; }
count() { grep -cE "$1" <<<"$2"; } # count lines matching
no5xx() { expect "$1: no 5xx" 0 "$(count ' 5[0-9][0-9]$' "$2")"; }

# ---------------------------------------------------------------- speakers

# One email is one speaker, also when 20 registrations (mixed case) arrive at once.
email="dup-$run@example.com"
codes=$(for i in $(seq 1 20); do
    e=$email
    ((i % 2)) && e=$(tr '[:lower:]' '[:upper:]' <<<"$email")
    req POST /speakers "{\"name\":\"Dup $i\",\"email\":\"$e\"}" &
done; wait)
expect "speakers: 20 parallel registrations of one email -> one 201" 1 "$(count ' 201$' "$codes")"
expect "speakers: the other 19 -> 409" 19 "$(count ' 409$' "$codes")"

# ---------------------------------------------------------------- acceptance

# Every scenario gets a fresh main speaker: the 3-talk limit would otherwise refuse the fourth talk.
rated() { # a talk with two reviewers, so it can be accepted
    local t
    t=$(talk "$(speaker accept)")
    req PUT "/talks/$t/ratings/1" '{"score":4}' >/dev/null
    req PUT "/talks/$t/ratings/2" '{"score":5}' >/dev/null
    echo "$t"
}

# Two committee members deciding at once: one final decision, never both.
t=$(rated)
codes=$(for i in $(seq 1 10); do
    req PUT "/talks/$t/status" '{"status":"ACCEPTED"}' ACCEPTED &
    req PUT "/talks/$t/status" '{"status":"REJECTED"}' REJECTED &
done; wait)
expect "acceptance: 10 accept + 10 reject at once -> exactly one 200" 1 "$(count ' 200$' "$codes")"
expect "acceptance: the final status is the winner's" "$(grep ' 200$' <<<"$codes" | cut -d' ' -f1)" "$(get "/talks/$t" 't["status"]')"
no5xx "acceptance: accept vs reject" "$codes"

# Reject and withdraw at once: a rejected talk can't be withdrawn and a withdrawn one can't be rejected.
main=$(speaker withdraw)
t=$(talk "$main")
codes=$(for i in $(seq 1 10); do
    req PUT "/talks/$t/status" '{"status":"REJECTED"}' REJECTED &
    req POST "/talks/$t/withdraw" "{\"speakerId\":$main}" WITHDRAWN &
done; wait)
expect "acceptance: 10 reject + 10 withdraw at once -> exactly one 200" 1 "$(count ' 200$' "$codes")"
expect "acceptance: final status is the winner's" "$(grep ' 200$' <<<"$codes" | cut -d' ' -f1)" "$(get "/talks/$t" 't["status"]')"
no5xx "acceptance: reject vs withdraw" "$codes"

# Waitlist and accept at once: waitlisting after an accept is not allowed, so an accepted talk stays accepted.
t=$(rated)
codes=$(for i in $(seq 1 10); do
    req PUT "/talks/$t/status" '{"status":"WAITLISTED"}' WAITLISTED &
    req PUT "/talks/$t/status" '{"status":"ACCEPTED"}' ACCEPTED &
done; wait)
expect "acceptance: waitlist vs accept -> at most one accept succeeds" 1 "$(count '^ACCEPTED 200$' "$codes")"
expect "acceptance: waitlist vs accept -> at most one waitlist succeeds" true \
    "$( (($(count '^WAITLISTED 200$' "$codes") <= 1)) && echo true || echo false)"
expect "acceptance: waitlist vs accept -> ends ACCEPTED" ACCEPTED "$(get "/talks/$t" 't["status"]')"
no5xx "acceptance: waitlist vs accept" "$codes"

# Only the main speaker can withdraw, also when others try at the same moment.
main=$(speaker owner)
other=$(speaker other)
t=$(talk "$main")
codes=$(for i in $(seq 1 5); do
    req POST "/talks/$t/withdraw" "{\"speakerId\":$other}" OTHER &
    req POST "/talks/$t/withdraw" "{\"speakerId\":$main}" MAIN &
done; wait)
expect "acceptance: withdraw by main + others at once -> one 200, by the main speaker" "MAIN 200" "$(grep ' 200$' <<<"$codes")"
expect "acceptance: others never succeed" 0 "$(count '^OTHER 2' "$codes")"
expect "acceptance: ends WITHDRAWN" WITHDRAWN "$(get "/talks/$t" 't["status"]')"
no5xx "acceptance: withdraw race" "$codes"

# ---------------------------------------------------------------- co-speakers

host=$(speaker host)
co=()
for i in 1 2 3 4 5 6; do co+=("$(speaker "co$i")"); done
cos() { get "/talks/$1" 't["coSpeakerIds"]'; }
norm() { tr -d ' ' <<<"$1"; }

# Six different speakers added at once: at most two land.
t=$(talk "$host")
codes=$(for s in "${co[@]}"; do req PUT "/talks/$t/co-speakers/$s" "" "$s" & done; wait)
expect "co-speakers: 6 distinct adds at once -> two 200" 2 "$(count ' 200$' "$codes")"
expect "co-speakers: the rest rejected with 4xx" 4 "$(count ' 4[0-9][0-9]$' "$codes")"
expect "co-speakers: the talk has exactly the two winners" \
    "$(grep ' 200$' <<<"$codes" | cut -d' ' -f1 | sort -n | paste -sd, -)" \
    "$(get "/talks/$t" '",".join(str(x) for x in sorted(t["coSpeakerIds"]))')"
no5xx "co-speakers: distinct adds" "$codes"

# The same speaker added ten times at once (double clicks): listed once.
t=$(talk "$host")
codes=$(for i in $(seq 1 10); do req PUT "/talks/$t/co-speakers/${co[0]}" "" & done; wait)
expect "co-speakers: 10 identical adds at once -> all 200" 10 "$(count ' 200$' "$codes")"
expect "co-speakers: listed once" "[${co[0]}]" "$(norm "$(cos "$t")")"

# Adding and removing the same speaker at once: no duplicates, no ghosts.
codes=$(for i in $(seq 1 10); do
    req PUT "/talks/$t/co-speakers/${co[1]}" "" ADD &
    req DELETE "/talks/$t/co-speakers/${co[1]}" "" DEL &
done; wait)
list=$(get "/talks/$t" 't["coSpeakerIds"]')
expect "co-speakers: add/remove storm -> no duplicates" true \
    "$(get "/talks/$t" 'str(len(t["coSpeakerIds"]) == len(set(t["coSpeakerIds"]))).lower()')"
expect "co-speakers: add/remove storm -> first co-speaker untouched, at most 2" true \
    "$(get "/talks/$t" "str(t['coSpeakerIds'][0] == ${co[0]} and len(t['coSpeakerIds']) <= 2).lower()")"
no5xx "co-speakers: add/remove storm" "$codes"

# Full talk: removing one while two others are added at once never goes past two.
t=$(talk "$host")
req PUT "/talks/$t/co-speakers/${co[0]}" >/dev/null
req PUT "/talks/$t/co-speakers/${co[1]}" >/dev/null
codes=$(for i in 1 2 3; do
    req DELETE "/talks/$t/co-speakers/${co[0]}" "" DEL &
    req PUT "/talks/$t/co-speakers/${co[2]}" "" ADD2 &
    req PUT "/talks/$t/co-speakers/${co[3]}" "" ADD3 &
done; wait)
expect "co-speakers: remove + 2 adds on a full talk -> still at most 2, no duplicates" true \
    "$(get "/talks/$t" 'str(len(t["coSpeakerIds"]) <= 2 and len(t["coSpeakerIds"]) == len(set(t["coSpeakerIds"]))).lower()')"
no5xx "co-speakers: full-talk swap" "$codes"

# Co-speaking doesn't use up submissions: a speaker on three other talks still gets exactly three of their own.
busy=${co[5]}
for i in 1 2 3; do
    o=$(speaker "owner$i")
    req PUT "/talks/$(talk "$o")/co-speakers/$busy" >/dev/null
done
codes=$(for i in $(seq 1 6); do req POST /talks "{\"title\":\"Own $i\",\"format\":\"WORKSHOP\",\"level\":\"BEGINNER\",\"speakerId\":$busy}" & done; wait)
expect "co-speakers: co-speaker on 3 talks submits 6 at once -> exactly 3 created" 3 "$(count ' 201$' "$codes")"
expect "co-speakers: own talk list shows only the 3 own talks" 3 "$(get "/speakers/$busy/talks" 'len(t)')"

# ---------------------------------------------------------------- workshop registration

# An accepted workshop: WORKSHOP format, two reviewers, accepted by the committee.
workshop() {
    local w
    w=$(post /talks "{\"title\":\"Race workshop\",\"format\":\"WORKSHOP\",\"level\":\"BEGINNER\",\"speakerId\":$(speaker host)}" | id)
    req PUT "/talks/$w/ratings/1" '{"score":4}' >/dev/null
    req PUT "/talks/$w/ratings/2" '{"score":5}' >/dev/null
    req PUT "/talks/$w/status" '{"status":"ACCEPTED"}' >/dev/null
    echo "$w"
}
register() { req POST "/workshops/$1/registrations" "{\"name\":\"Attendee\",\"email\":\"$2\"}" "${3:-$2}"; }
regs() { get "/workshops/$1/registrations" "$2"; }

w=$(workshop)
if [[ $(register "$w" "probe-$run@example.com" | cut -d' ' -f2) == 404 ]]; then
    echo "skip  workshop registration: POST /workshops/{id}/registrations answers 404 (not implemented yet)"
else
    # Registration opens to everyone at once: 50 different people, 20 seats (the probe already took one).
    codes=$(for i in $(seq 1 50); do register "$w" "rush-$i-$run@example.com" & done; wait)
    expect "workshops: 50 at once on a workshop with 19 seats left -> 19 created" 19 "$(count ' 201$' "$codes")"
    expect "workshops: the other 31 rejected with 4xx" 31 "$(count ' 4[0-9][0-9]$' "$codes")"
    no5xx "workshops: rush" "$codes"
    expect "workshops: the list has exactly 20" 20 "$(regs "$w" 'len(t)')"
    expect "workshops: every email on the list once" true "$(regs "$w" 'str(len({r["email"].lower() for r in t}) == len(t)).lower()')"
    expect "workshops: the list is in registration order (ids ascending)" true \
        "$(regs "$w" 'str([r["id"] for r in t] == sorted(r["id"] for r in t)).lower()')"
    expect "workshops: the 201s are exactly the people on the list" \
        "$( (echo "probe-$run@example.com"; grep ' 201$' <<<"$codes" | cut -d' ' -f1) | sort | paste -sd, -)" \
        "$(regs "$w" '",".join(sorted(r["email"] for r in t))')"

    # One person double-clicking, typing their email in different cases: registered once.
    w=$(workshop)
    email="Ada-$run@Example.com"
    codes=$(for i in $(seq 1 10); do
        e=$email
        ((i % 3 == 0)) && e=$(tr '[:upper:]' '[:lower:]' <<<"$email")
        ((i % 3 == 1)) && e=$(tr '[:lower:]' '[:upper:]' <<<"$email")
        register "$w" "$e" &
    done; wait)
    expect "workshops: one email (mixed case) 10x at once -> one 201" 1 "$(count ' 201$' "$codes")"
    expect "workshops: the duplicates rejected with 4xx" 9 "$(count ' 4[0-9][0-9]$' "$codes")"
    expect "workshops: listed once" 1 "$(regs "$w" 'len(t)')"
    no5xx "workshops: duplicate email" "$codes"

    # A rush mixing new people and duplicates: still 20 seats, still one per person.
    w=$(workshop)
    codes=$(for i in $(seq 1 30); do
        register "$w" "mix-$i-$run@example.com" &
        register "$w" "MIX-$i-$run@EXAMPLE.COM" &
    done; wait)
    expect "workshops: 30 people x 2 spellings at once -> 20 created" 20 "$(count ' 201$' "$codes")"
    expect "workshops: list 20, nobody twice" "20 true" \
        "$(regs "$w" '"%d %s" % (len(t), str(len({r["email"].lower() for r in t}) == len(t)).lower())')"
    no5xx "workshops: mixed rush" "$codes"

    # The same person on several workshops at once: one seat on each.
    w1=$(workshop)
    w2=$(workshop)
    codes=$(for i in 1 2 3; do register "$w1" "both-$run@example.com" W1 & register "$w2" "both-$run@example.com" W2 & done; wait)
    expect "workshops: one person on two workshops at once -> one 201 on each" "W1 201,W2 201" \
        "$(grep ' 201$' <<<"$codes" | sort | paste -sd, -)"

    # Withdrawn while the rush is on: never more than 20, nobody dropped, no 5xx.
    w=$(workshop)
    host_of_w=$(get "/talks/$w" 't["speakerId"]')
    codes=$(for i in $(seq 1 30); do
        register "$w" "late-$i-$run@example.com" &
        ((i == 10)) && req POST "/talks/$w/withdraw" "{\"speakerId\":$host_of_w}" WITHDRAW &
    done; wait)
    expect "workshops: withdrawn mid-rush -> still at most 20 and the list matches the 201s" true \
        "$(regs "$w" "str(len(t) <= 20 and len(t) == $(count '@example.com 201$' "$codes")).lower()")"
    no5xx "workshops: withdraw mid-rush" "$codes"

    # Only accepted workshops take registrations.
    t=$(talk "$(speaker notworkshop)")
    req PUT "/talks/$t/ratings/1" '{"score":4}' >/dev/null
    req PUT "/talks/$t/ratings/2" '{"score":5}' >/dev/null
    req PUT "/talks/$t/status" '{"status":"ACCEPTED"}' >/dev/null
    s=$(post /talks "{\"title\":\"Submitted workshop\",\"format\":\"WORKSHOP\",\"level\":\"BEGINNER\",\"speakerId\":$(speaker sub)}" | id)
    codes=$( (for i in 1 2 3; do register "$t" "x-$i-$run@example.com" NOTWORKSHOP & register "$s" "y-$i-$run@example.com" SUBMITTED & done; wait) )
    expect "workshops: accepted presentation and submitted workshop -> no registration lands" 0 "$(count ' 2[0-9][0-9]$' "$codes")"
    no5xx "workshops: not open" "$codes"
fi

# ---------------------------------------------------------------- scheduling (local only)

# The programme is one global grid that the harness uses too: never race on it in production.
if [[ $base == http://localhost* || $base == http://127.0.0.1* ]]; then
    clear_programme() { for r in R1 R2 R3; do for s in S1 S2 S3 S4 S5; do req DELETE "/programme/$r/$s" >/dev/null; done; done; }
    accepted() { # an accepted talk of the given main speaker
        local t
        t=$(post /talks "{\"title\":\"Race prog\",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\",\"speakerId\":$1}" | id)
        req PUT "/talks/$t/ratings/1" '{"score":4}' >/dev/null
        req PUT "/talks/$t/ratings/2" '{"score":4}' >/dev/null
        req PUT "/talks/$t/status" '{"status":"ACCEPTED"}' >/dev/null
        echo "$t"
    }
    clear_programme

    # Ten committee members, ten talks, one cell: one wins.
    talks=(); for i in $(seq 1 10); do talks+=("$(accepted "$(speaker prog$i)")"); done
    codes=$(for t in "${talks[@]}"; do req PUT /programme/R1/S1 "{\"talkId\":$t}" "$t" & done; wait)
    expect "scheduling: 10 talks into one cell at once -> one 200" 1 "$(count ' 200$' "$codes")"
    expect "scheduling: the cell holds the winner" "$(grep ' 200$' <<<"$codes" | cut -d' ' -f1)" "$(get /programme/R1/S1 't["talkId"]')"
    no5xx "scheduling: one cell" "$codes"
    clear_programme

    # One talk into five cells at once (five different slots, so five different locks): it's placed once.
    t=$(accepted "$(speaker once)")
    codes=$(for s in S1 S2 S3 S4 S5; do req PUT "/programme/R2/$s" "{\"talkId\":$t}" "$s" & done; wait)
    expect "scheduling: one talk into 5 slots at once -> one 200" 1 "$(count ' 200$' "$codes")"
    placed=0; for s in S1 S2 S3 S4 S5; do [[ $(get "/programme/R2/$s" 't["talkId"]') == "$t" ]] && placed=$((placed + 1)); done
    expect "scheduling: on the programme once" 1 "$placed"
    no5xx "scheduling: one talk, five cells" "$codes"
    clear_programme

    # One speaker's three talks into the three rooms of one slot at once: one speaks, the others are refused.
    main=$(speaker busy)
    a=$(accepted "$main"); b=$(accepted "$main"); c=$(accepted "$main")
    codes=$( (req PUT /programme/R1/S2 "{\"talkId\":$a}" R1 & req PUT /programme/R2/S2 "{\"talkId\":$b}" R2 &
              req PUT /programme/R3/S2 "{\"talkId\":$c}" R3 & wait) )
    expect "scheduling: one speaker into 3 rooms of a slot at once -> one 200" 1 "$(count ' 200$' "$codes")"
    no5xx "scheduling: speaker clash" "$codes"
    clear_programme
fi

exit $fail
