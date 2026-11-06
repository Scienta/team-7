# Refactor backlog

Worked top to bottom by the refactor loop (`.claude/skills/refactor-step/SKILL.md`), one item per iteration.
`[ ]` open, `[x]` done (commit + result), `[!]` blocked (why). Items tagged **needs-human** change API behaviour,
the schema or security and are never picked by the loop: a person decides and does them with an agent in the chat.

Kinds: **perf** (must improve a measured number), **refactor** (no behaviour change: `compare.sh` equivalent),
**observability** (logs only).

Baseline 2026-11-06 at `8b4cefa`, 60 VUs, 1500 talks: ~1020 req/s, every endpoint p95 ~130-160 ms, even
`GET /speakers/{id}`, which suggests requests queue for the 5 pool connections rather than run slow queries.

## Open

- [x] **R1 perf: find where requests wait.** Finding (2026-11-06, 60 users, production-shaped data): the pool of 5 is
  saturated all the time, up to 66 requests waiting; over a run requests spent 802 s waiting for a connection and
  143 s using one. `GET /speakers/{id}` does 2 ms of work and waits ~100 ms. Per request: the full talk lists hold
  a connection ~6-7 ms (10x anything else, ~80 % of all connection time in the load mix), and every talk read took
  2 connections (talks, then co-speakers in a second query). Fixed the second part: co-speakers come in the talk
  query (`array_agg`), 1 connection per talk read. Median of 3 runs: 959 -> 1024 req/s, `GET /speakers/{id}` p95
  110 -> 91 ms, `GET /talks/{id}` 128 -> ~105 ms; list p95 unchanged within noise. Compare equivalent.
  Left: the lists' hold time itself (serving ~4000 talks per request) - see R8.
- [ ] **R8 perf: the full talk lists dominate connection time.** ~6 ms held per `GET /talks` at ~4000 talks and
  growing with the data. Options: build the JSON from one SQL query (`json_agg`), page or cache the list, or keep
  rating/co-speaker aggregates on the talk row (a schema change: needs-human).
- [ ] **R9 tooling: list p95 is too noisy for one run.** On the same code, `GET /talks` p95 varied 378-594 ms across
  runs and the old build flagged itself REGRESSED. `perf.sh` should take the median of 3 runs (or compare
  throughput and the cheap endpoints, which are stable) before calling a regression.
- [ ] **R2 perf: fewer queries per write.** `submitTalk`: exists + lock + insert + speaker lookup + read-back;
  `rate`: exists + upsert; `registerSpeaker`: emailTaken + insert (the unique index already decides). Cut round
  trips without weakening the 3-talk limit or the upsert (race.sh and load.js invariants must stay green).
  Done when: write p95 improves, compare equivalent.
- [x] **R3 observability: request logging.** One line per request: method, path with query, status, duration ms.
  No bodies, no emails. A servlet filter. Done when: lines show in `.local/app.log` for check.sh traffic and perf
  doesn't regress.
- [ ] **R4 refactor: explicit date formats.** `submittedAt` (`yyyy-MM-dd'T'HH:mm:ss.SSS+00:00`), `submittedOn`
  (`yyyy-MM-dd`, UTC) and `ratedAtDisplay` (`M/d/yy, h:mm a`, US, UTC, ordinary space) via `DateTimeFormatter`,
  not Jackson/JDK/JVM-timezone defaults. Unit tests incl. midnight (`12:00 AM`), noon, a UTC day boundary, and
  running under a non-UTC default timezone. Done when: compare equivalent, tests green.
- [ ] **R5 refactor: remove dead and misleading code.** `Utils.MAX_TALKS = 5` (the limit is 3), the duration
  `switch` duplicating `DURATIONS` (with a silent 45 default), commented-out code, unused helpers, the stale API and
  run sections of the README. Keep `Utils.roundRating` behaviour for `/admin/stats` (or make it use
  `averageRating` only as a needs-human change). Done when: compare equivalent.
  Partly done 2026-11-06: `Utils.MAX_TALKS` removed (the limit is `ConferenceService.MAX_TALKS = 3`), the duration
  `switch` replaced by `DURATIONS`, the README's run/API sections rewritten, `Utils.today()` in UTC. Left:
  commented-out code and unused helpers.
- [ ] **R6 refactor: split `ConferenceService`** into speaker, talk, rating and report services; controllers and
  JSON unchanged. Done when: compare equivalent, all tests green.
- [ ] **R7 refactor: typed responses.** Speaker and rating responses as classes instead of `Map<String,Object>`,
  same field order and null handling. Done when: compare equivalent.

## Needs a human decision (the loop skips these)

- [ ] **H1 needs-human: error bodies leak internals.** `include-message=always` shows SQL and driver errors on 500s;
  `include-binding-errors=always` echoes rejected input. Changes response bodies; the comment says a mobile client
  depended on them.
- [ ] **H2 needs-human: strict JSON and consistent status codes.** Jackson coerces `"1"` to a number and `123` to
  a string, allows comments; unknown speaker is 400 on POST /talks, 404 on /speakers/{id}/talks, `[]` on
  `/talks?speakerId=`.
- [ ] **H3 needs-human: foreign keys.** `talk.speaker_id`, `rating.talk_id` unenforced. Deployed data may already
  violate them, which would make the migration fail at start-up.
- [ ] **H4 needs-human: unauthenticated admin endpoints.** `PUT /speakers/{id}`, `/admin/*`.

## Log

<!-- One line per iteration: date time, item, done/blocked, commit, the numbers. -->
- 2026-11-06, R3, done: `RequestLoggingFilter` (logger `access`), e.g. `POST /speakers/ 201 12ms`; check.sh ALL GREEN.
- 2026-11-06, R5, partly: dead `MAX_TALKS`, duration `switch`, stale README sections; check.sh ALL GREEN, CSV identical.
