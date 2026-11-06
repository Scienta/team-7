# Refactor backlog

Worked top to bottom by the refactor loop (`.claude/skills/refactor-step/SKILL.md`), one item per iteration.
`[ ]` open, `[x]` done (commit + result), `[!]` blocked (why). Items tagged **needs-human** change API behaviour,
the schema or security and are never picked by the loop: a person decides and does them with an agent in the chat.

Kinds: **perf** (must improve a measured number), **refactor** (no behaviour change: `compare.sh` equivalent),
**observability** (logs only).

Baseline 2026-11-06 at `8b4cefa`, 60 VUs, 1500 talks: ~1020 req/s, every endpoint p95 ~130-160 ms, even
`GET /speakers/{id}`, which suggests requests queue for the 5 pool connections rather than run slow queries.

## Open

- [ ] **R1 perf: find where requests wait.** Measure before changing: Hikari pool metrics (active/pending, wait
  time) under `perf.sh`, Tomcat threads, which requests hold a connection longest (`@Transactional` around
  `submitTalk` holds the speaker row lock and a connection across 5 queries and the notification). Write the
  finding in this item, then fix the biggest cause. Pool max stays 5 (platform cap).
  Done when: p95 of the cheap endpoints (`GET /speakers/{id}`, `GET /talks/{id}`) clearly improves, compare equivalent.
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
