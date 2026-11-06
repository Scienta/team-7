#!/usr/bin/env bash
# Does the local test data still look like production's? Profiles both through the API (we have no direct access to
# the deployed database): volume, format/level/status mix, co-speakers, ratings per talk, accepted workshops. Exits 1
# and lists what drifted when local no longer resembles production, so tests and load runs aren't exercising
# unrealistic data.
#
# Usage: workflow/bin/dbdrift.sh                 compare and report (exit 1 on drift)
#        workflow/bin/dbdrift.sh --sync          rebuild the local test data in production's shape (synthetic rows,
#                                                same volume and mixes; the local database is scratch)
#        workflow/bin/dbdrift.sh --watch [secs]  compare every secs (default 600) and exit on the first drift
# Env:   PROD (default the team URL), LOCAL (default http://localhost:8080)
set -uo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
PROD=${PROD:-https://fagsem-h2026-team-7.scienta.cloud}
LOCAL=${LOCAL:-http://localhost:8080}
mkdir -p .local/drift

profile() { # base-url -> JSON profile on stdout
    curl -fsS "$1/talks" >".local/drift/talks.json" || return 1
    python3 - <<'EOF'
import json, collections
t = json.load(open(".local/drift/talks.json"))
n = len(t) or 1
def mix(key):
    c = collections.Counter(x[key] for x in t)
    return {k: round(v / n, 3) for k, v in sorted(c.items())}
rc = [x["ratingCount"] for x in t]
hist = collections.Counter(min(r, 20) for r in rc)
print(json.dumps({
    "talks": len(t),
    "speakers": len({x["speakerId"] for x in t}),
    "format": mix("format"), "level": mix("level"), "status": mix("status"),
    "coSpeakers": round(sum(1 for x in t if x.get("coSpeakerIds")) / n, 3),
    "rated": round(sum(1 for r in rc if r) / n, 3),
    "ratingsPerRatedTalk": round(sum(rc) / max(1, sum(1 for r in rc if r)), 2),
    "maxRatings": max(rc or [0]),
    "ratingHistogram": {str(k): round(v / n, 4) for k, v in sorted(hist.items())},
    "acceptedWorkshops": sum(1 for x in t if x["format"] == "WORKSHOP" and x["status"] == "ACCEPTED"),
}))
EOF
}

compare() {
    profile "$PROD" >.local/drift/prod.json || { echo "can't read $PROD/talks" >&2; return 2; }
    profile "$LOCAL" >.local/drift/local.json || { echo "can't read $LOCAL/talks (local.sh up?)" >&2; return 2; }
    python3 - <<'EOF'
import json, sys
p = json.load(open(".local/drift/prod.json"))
l = json.load(open(".local/drift/local.json"))
drift = []
ratio = l["talks"] / max(1, p["talks"])
if not 0.75 <= ratio <= 1.5:
    drift.append(f"volume: {l['talks']} local talks vs {p['talks']} in production")
for key in ("format", "level", "status"):
    for k in sorted(set(p[key]) | set(l[key])):
        a, b = p[key].get(k, 0), l[key].get(k, 0)
        if abs(a - b) > 0.10 or (a > 0.01 and b == 0):
            drift.append(f"{key} {k}: {b:.0%} local vs {a:.0%} in production")
for key, label in (("coSpeakers", "talks with co-speakers"), ("rated", "talks rated")):
    if abs(p[key] - l[key]) > 0.10 or (p[key] > 0.01 and l[key] == 0):
        drift.append(f"{label}: {l[key]:.0%} local vs {p[key]:.0%} in production")
if l["maxRatings"] < p["maxRatings"] / 2:
    drift.append(f"most ratings on one talk: {l['maxRatings']} local vs {p['maxRatings']} in production")
if p["acceptedWorkshops"] and l["acceptedWorkshops"] < p["acceptedWorkshops"] / 2:
    drift.append(f"accepted workshops: {l['acceptedWorkshops']} local vs {p['acceptedWorkshops']} in production")
print(f"production: {p['talks']} talks, local: {l['talks']} talks")
if drift:
    print("DRIFT: local data no longer looks like production (workflow/bin/dbdrift.sh --sync to rebuild):")
    for d in drift:
        print("  " + d)
    sys.exit(1)
print("IN SHAPE: local data resembles production")
EOF
}

sync() {
    profile "$PROD" >.local/drift/prod.json || { echo "can't read $PROD/talks" >&2; exit 2; }
    # Synthetic rows in production's shape. Seed titles keep compare.sh's fixed reads working.
    python3 - >.local/drift/seed.sql <<'EOF'
import json, random
random.seed(42)
p = json.load(open(".local/drift/prod.json"))
n = p["talks"]
def pick(dist):
    r, acc = random.random(), 0.0
    for k, v in dist.items():
        acc += v
        if r < acc:
            return k
    return list(dist)[-1]
durations = {"LIGHTNING_10": 10, "LIGHTNING_20": 20, "PRESENTATION": 45, "WORKSHOP": 120}
hist = {int(k): v for k, v in p["ratingHistogram"].items()}
print("BEGIN;")
print("TRUNCATE rating, talk_co_speaker, registration, talk, speaker RESTART IDENTITY;")
# The programme is a fixed grid created at start-up: empty its cells, keep the rows.
print("UPDATE programme SET talk_id = NULL;")
speakers = max(3, int(n / 1.5))
print(f"INSERT INTO speaker (name, email) SELECT 'Seed ' || g, 'seed-' || g || '@example.com' FROM generate_series(1, {speakers}) g;")
per_speaker = {}
rows, ratings, cos, regs = [], [], [], []
for talk in range(1, n + 1):
    while True:
        s = random.randint(1, speakers)
        if per_speaker.get(s, 0) < 3:
            per_speaker[s] = per_speaker.get(s, 0) + 1
            break
    fmt, lvl, status = pick(p["format"]), pick(p["level"]), pick(p["status"])
    count = pick(hist)
    if status == "ACCEPTED" and count < 2:
        count = 2  # the rules: accepted talks have two reviewers
    rows.append(f"('Seed talk','{fmt}',{durations[fmt]},'{lvl}','{status}',{s},(extract(epoch from now())*1000)::bigint - {n - talk})")
    for r in range(1, count + 1):
        ratings.append(f"({talk},{r},{random.randint(1, 5)},'2026-11-06 12:00:00')")
    if random.random() < p["coSpeakers"]:
        for c in random.sample([x for x in range(1, speakers + 1) if x != s], random.choice([1, 2])):
            cos.append(f"({talk},{c})")
    if fmt == "WORKSHOP" and status == "ACCEPTED":
        for a in range(random.randint(0, 20)):
            regs.append(f"({talk},'Attendee {a}','att-{talk}-{a}@example.com')")
def insert(table, cols, values):
    for i in range(0, len(values), 1000):
        print(f"INSERT INTO {table} ({cols}) VALUES " + ",".join(values[i:i + 1000]) + ";")
insert("talk", "title, format, duration, level, status, speaker_id, submitted_at", rows)
insert("rating", "talk_id, reviewer_id, score, rated_at", ratings)
insert("talk_co_speaker", "talk_id, speaker_id", cos)
insert("registration", "talk_id, name, email", regs)
print("COMMIT;")
EOF
    docker compose exec -T postgres psql -U cfp -q -v ON_ERROR_STOP=1 <.local/drift/seed.sql >/dev/null ||
        { echo "seeding failed; the schema may have changed (check workflow/bin/dbdrift.sh's INSERTs)" >&2; exit 1; }
    echo "local data rebuilt in production's shape: $(docker compose exec -T postgres psql -U cfp -tAc 'SELECT COUNT(*) FROM talk') talks"
}

case "${1:-}" in
    --sync) sync && compare ;;
    --watch)
        while compare >.local/drift/last.txt 2>&1; do sleep "${2:-600}"; done
        cat .local/drift/last.txt
        ;;
    "") compare ;;
    *) sed -n '2,13p' "$0"; exit 2 ;;
esac
