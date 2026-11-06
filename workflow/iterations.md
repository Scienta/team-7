# Iterations

One entry per fix pushed to `main`: the problem, the change, the evidence before pushing, and what the deployed
service showed after. Newest last. Gate: Docker build (JDK 25, as deployed), `check.sh` (Hurl + races),
`compare.sh` against the previous commit (31 reads and rejected writes), plus timings for perf changes.

## 1. Talk lists slowed down with every talk (`d4cbc84`)

- **Problem:** `GET /talks` took 4.9 s on the deployed service and grew every tick: each talk ran its own ratings
  query, so 1500 talks meant 1500 queries.
- **Change:** `TalkDao` reads talks with their rating count and total in one query; `ConferenceService` no longer
  looks ratings up per talk. 2 files, +21/-25.
- **Evidence:** locally at 1523 talks 0.137 s → 0.012 s; equivalent to `5ff7872` on 31 requests; all Hurl suites
  green except G2 (iteration 2).
- **Deployed:** deploy green; `GET /talks` on the deployed service 4.9 s → 0.36-0.54 s.

## 2. A bio of 500 emoji was "too long"

- **Problem:** the spec allows 500 characters; `@Size` counts UTF-16 units, so an emoji counts twice and 500 emoji
  were rejected with 400.
- **Change:** the bio is counted in code points, on register and on update (update allowed 1000, now 500 like
  register). 2 files, +11/-6.
- **Evidence:** 500 emoji: 400 → 201; `speakers-bio.hurl` green, all 17 Hurl files and the race checks green;
  equivalent to `d4cbc84` on 31 requests.
- **Deployed:** deploy and CI JUnit green; 500 emoji → 201 on the deployed service.

## 3. The local loop assumed JDK 11; the app now runs on JDK 25

- **Problem:** after the teammate's upgrade to JDK 25 / Spring Boot 3, `local.sh` and `compare.sh` still built with
  a local JDK 11, so local results no longer matched what's deployed (and no JDK 25 is installed here).
- **Change:** `local.sh up|image|ref` and `compare.sh` build and run with the root `Dockerfile`, so the local app
  always has the deployed JDK; `workflow/specs/` dropped for the teammate's `specs/`; rules and the refactor-step
  skill now say: small slices, push right after a green gate, report Problem / Change / Evidence / Pushed.
- **Evidence:** `local.sh up` + `compare.sh` against `0f08036`: equivalent, 7.5 s with warm images; `check.sh` below.
- **Deployed:** tooling only, no service change.

## 4. Co-speakers checks 1 and 2 failed right after the claim

- **Problem:** co-speakers was claimed at tick 47; at tick 48 `co-speakers/check-1` failed on a second
  `DELETE /talks/{id}/co-speakers/{speakerId}` of the same co-speaker (we answered `200`, talk unchanged) and
  `check-2` on `PUT` of the talk's main speaker (we answered `409`). The harness shows the failing request, not the
  expected status.
- **Change:** removing someone who isn't a co-speaker → `404`; adding the main speaker → `400`. `CoSpeakerDao.remove`
  reports whether a row went; `ConferenceService` throws. Tests, `examples/co-speakers.hurl` and the team decisions
  in `specs/co-speakers.md` updated. Still guesses: `409` for a third co-speaker, `404` for an unknown speaker.
- **Evidence:** JUnit 43 green (JDK 25, Testcontainers); `check.sh` ALL GREEN against the jar on JDK 25 (this
  sandbox's Docker builds can't reach Maven Central, so `local.sh up` and `compare.sh` weren't run).
- **Deployed:** pending merge.

## 5. Spring Boot 4 (drip feature `spring-boot-4`)

- **Problem:** the service ran Spring Boot 3.5.16; the feature request asks for Boot 4 with the base specs' formats
  unchanged and a truthful `/actuator/info`. A straight upgrade broke the wire format: Jackson 3 wrote
  `submittedAt` as `…824Z` (spec: `+00:00`), `submittedOn` as a timestamp in the server's zone
  (`2026-11-05T23:00:00.000Z`, the wrong day), sorted fields alphabetically, and error bodies lost `message`.
- **Change:** Boot 4.1.2 (`spring-boot-starter-webmvc`, Testcontainers 2); `submittedAt`/`submittedOn` are strings
  formatted in UTC by `Utils.legacyTimestamp`/`legacyDate`; `spring.jackson.mapper.sort-properties-alphabetically=false`;
  `server.error.*` → `spring.web.error.*`; `spring.jackson.json.read.allow-java-comments`. New `WireFormatTest` pins
  the exact JSON of the base specs and the running versions on `/actuator/info`.
- **Evidence:** JUnit 52 green (1 skipped, the old disabled one); `check.sh` ALL GREEN on :8080 and on the
  linux/amd64 image on :8081 (`/actuator/info`: java 25.0.5, springBoot 4.1.2, springFramework 7.0.10);
  `compare.sh origin/main`: 30 of 31 same, the one diff is Jackson's wording inside a malformed-JSON 400 message.
- **Pushed:** see below.

## 6. No race checks for acceptance, co-speakers or duplicate registrations

- **Problem:** `race.sh` covered only the 3-talk limit and ratings. Acceptance and co-speakers (teammates' PRs #1-#2)
  both claim concurrency safety (conditional `UPDATE`, row lock + unique key) that nothing of ours tried to break.
- **Change:** 29 new parallel checks in `workflow/bin/race.sh`: 20 mixed-case registrations of one email; 10 accept +
  10 reject, 10 reject + 10 withdraw, 10 waitlist + 10 accept, and withdraw by owner + others on one talk; 6 distinct
  co-speakers at once, 10 identical adds, add/remove storms, remove + 2 adds on a full talk, and a speaker co-speaking
  on 3 talks submitting 6 of their own at once. Every burst also asserts no 5xx. `watch.sh` now also watches
  `origin/main` and polls every 10 s.
- **Evidence:** 36/36 green, 6 runs locally on `6ff8b1d` and 4 runs against the deployed service. No race found: the
  locking holds.
- **Deployed:** tooling only.

## 7. `/admin/stats` ran one query per speaker

- **Problem:** `talks-list-speed.hurl` failed against the deployed service: `GET /admin/stats` took 2.58 / 2.69 /
  2.96 s (budget 1 s), growing with data. `speakersWithTalks` looked up each of the 1276 speakers with talks one by
  one (`speakerDao.findById` in the loop).
- **Change:** one `COUNT(*) … WHERE EXISTS` query, `SpeakerDao.countWithTalks`; the loop no longer touches the
  database. 2 files, +7/-5; `WireFormatTest.statsCountEachSpeakerWithTalksOnce`.
- **Evidence:** JUnit 53 green; `check.sh` ALL GREEN locally; `compare.sh`: EQUIVALENT to `841f28e` on 31 requests,
  `/admin/stats` included.
- **Pushed:** see below.

## 8. The CSV export looked up the speaker once per talk

- **Problem:** `/admin/talks.csv` took 5.4-6.0 s on the deployed service: it read each talk's speaker with its own
  query (the same pattern as `/admin/stats`, which the previous entry fixed).
- **Change:** `SpeakerDao.names()` reads every speaker's name in one query and the CSV takes names from that map
  (still 404 if a talk's speaker is missing). 3 files.
- **Evidence:** old and new jar side by side on 1533-1580 talks: CSV byte-identical; ~230 → ~25 ms locally.
- **Deployed:** pending merge.

## 9. No request logging (backlog R3)

- **Problem:** the app logged no requests, so a FAULTY check or a timeout (speakers/check-4 at tick 36) left nothing
  in the logs to see what was sent, what we answered or how long it took.
- **Change:** `RequestLoggingFilter`, first in the chain: `<method> <path?query> <status> <ms>` on logger `access`,
  path as sent (before the trailing slash is stripped), no bodies; an escaping exception logs as 500.
  `WebConfig` registers it before the trailing-slash filter. 2 files + `RequestLoggingFilterTest`.
- **Evidence:** live lines for 201/200/404/400 incl. `POST /speakers/ 201`; unit test for the 500 path;
  check.sh ALL GREEN.
- **Deployed:** pending merge. Read with `.github/fagdag/logs.sh | grep ' access '`.

## 10. Two speakers updated to the same email at once: a 500

- **Problem:** `PUT /speakers/{id}` checks the email is free, then updates; under concurrent updates to one email the
  loser hit the unique index and got `500` (with the SQL in the message) instead of `409` like registration.
- **Change:** `updateSpeaker` turns the `DuplicateKeyException` into `409`. 1 file + a race test in `SpeakersTest`.
- **Evidence:** `concurrentUpdatesToOneEmailHaveOneWinnerAndNo500` (10 parallel PUTs, one email): without the fix
  3/3 runs failed with a 500, with it 3/3 green.
- **Deployed:** pending merge.

## 11. Dead and misleading code (backlog R5, part)

- **Problem:** `Utils.MAX_TALKS = 5` (unused; the limit is 3, hard-coded twice), a duration `switch` duplicating
  `DURATIONS` with a silent 45-minute default, `Utils.today()` in the JVM's time zone, and a README describing Java 8,
  port 8081, votes and Jenkins.
- **Change:** one `MAX_TALKS = 3` in `ConferenceService`, durations from `DURATIONS`, `today()` in UTC (the platform
  runs in UTC, so no visible change there), README run/API/test/deploy sections rewritten.
- **Evidence:** JUnit 46 green; check.sh ALL GREEN; `/admin/talks.csv` byte-identical to `c8929ce` on 1533 talks.
- **Deployed:** pending merge.

## 12. The test suite spent most of its time waiting for timeouts

- **Problem:** `./mvnw verify` took 40 s locally (59 s in CI, where it gates every deploy); `HealthTest` was 26 s of
  it, mostly three sequential health checks against a paused database, each waiting out the 5 s timeout.
- **Change:** `HealthTest` sends five probes at the same time (5 s instead of 15 s; they now really overlap, which is
  the pile-up it guards against); surefire runs test classes in two JVMs (`forkCount=2`), so `HealthTest` runs
  beside the rest.
- **Evidence:** 40 s → 24.5-25.2 s over 3 runs, all 55 tests. With the single-flight probe broken (a thread per
  request), the new `HealthTest` fails with "5 health-check threads".
- **Deployed:** CI-only change; pending merge.

## 13. The deploy waited for the tests before it even started building the image

- **Problem:** `deploy` ran after `quality` (the tests, ~1 min) and then built the Docker image from scratch on a
  fresh runner: Maven image pull, every dependency downloaded, everything compiled again, all on the critical path.
- **Change:** a new `image` job builds and pushes `app:<sha>` in parallel with `quality`, with Buildx and the GitHub
  Actions layer cache (the dependency layer is reused until `pom.xml` changes). `deploy` needs both, then points
  `app:latest` at `app:<sha>` (pull/tag/push, as `rollout.sh` does for a rollback) and rolls out. `:latest` still
  never holds an untested image.
- **Evidence:** `actionlint` clean; job graph checked. Not run yet: forks get no Scaleway secrets and this sandbox
  can't reach the registry. **Watch the first run on main**; `git revert` restores the old single build.
- **Deployed:** pending merge.

## 5. Race checks for workshop registration, ahead of the feature

- **Problem:** workshop registration (released at tick 67) caps each workshop at 20 and expects "a rush" at opening;
  one registration per email whatever the case. A capacity check that reads then inserts would oversell.
- **Change:** a workshop section in `workflow/bin/race.sh` (22 checks): 50 people at once on a workshop with 19 seats
  left; one email 10x in mixed case; 30 people x 2 spellings at once; one person on two workshops at once; withdrawn
  mid-rush; registrations for an accepted non-workshop and a submitted workshop. It skips while the endpoint answers
  404.
- **Evidence:** skips cleanly on `9680a89` (36 existing checks green). Not yet run against an implementation.
- **Pushed:** held until a teammate pushes (per the user), then rebased on top of their push.

## 15. Workshop registration (drip feature `workshop-registration`)

- **Problem:** new feature at tick 67: attendees register for accepted workshops, 20 seats each under a rush, one
  per email (any case) per workshop, list in registration order.
- **Change:** `POST`/`GET /workshops/{id}/registrations` (`WorkshopController`, `WorkshopService`,
  `RegistrationDao`); table `registration` with a unique index on `(talk_id, lower(email))`. Registration locks
  the workshop's talk row (`FOR UPDATE`), then checks status, duplicate and count. Status codes in the new
  `workflow/decisions/workshop-registration.md`: 404 not a workshop, 400 bad name/email, 409 not accepted /
  duplicate / full.
- **Evidence:** `WorkshopRegistrationSpecTest` (29 black-box tests from the spec and decisions, written by the
  spec-test-writer recipe without reading `src/main`) and JUnit 85 green; 5 new `workflow/hurl/workshops-*.hurl`
  and the teammate's `race.sh` workshop checks (`84d31fd`);
  `check.sh` ALL GREEN on :8080 and on the linux/amd64 image; `compare.sh` EQUIVALENT to `9680a89` (31 requests).
- **Pushed:** see below.

## 6. Local test data didn't look like production's

- **Problem:** locally 1880 talks, 86 % presentations, 97 % beginner, almost all SUBMITTED, no co-speakers, every
  seeded talk with exactly 5 ratings. Production: 2714 talks, an even format and level mix, 142 accepted / 76
  withdrawn / 61 rejected, 240 talks with co-speakers, up to 20 ratings on a talk, 70 accepted workshops taking
  registrations. Tests and load runs exercised unrealistic data; `perf.sh` even reported a false regression because
  the data had grown under its baseline (A/B on identical data: the current build is ~10 % faster than this morning's).
- **Change:** `workflow/bin/dbdrift.sh` profiles production and local through the API and reports drift; `--sync`
  rebuilds local data in production's shape in ~2 s; `--watch` runs it every 10 minutes. `perf.sh` uses
  production-shaped data and records the talk count with its baseline, refusing to compare across different data.
- **Evidence:** drift report before (10 drifted dimensions), `IN SHAPE` after `--sync`; `check.sh` 22/22 + races and
  `compare.sh` equivalent on the new data; new baseline on 2809 talks: 947 req/s, all p95 under 360 ms.
- **Pushed:** see git log.

## 17. A fix for a failing check waited for the whole test suite

- **Problem:** a fix that stops us losing points deploys only after the test gate (`quality`, ~1 min), then the
  deploy job pulls the image again just to tag it `:latest`.
- **Change:** a fast lane in `deploy.yml`: `[fast]` in the pushed commit's message (or the `fast` input on a manual
  run) skips `quality`, the image job pushes `:latest` itself, and deploy starts as soon as the image is pushed.
  The build and the rollout's `/health` check and rollback stay. Documented in `AGENTS.md`.
- **Evidence:** `actionlint` clean; the job conditions walked through for a normal push, a failing test, `[fast]`,
  a manual fast run and a failed build. Not run yet (only `main` deploys).
- **Deployed:** pending merge; the first `[fast]` push is its real test.

## 7-8. workshop-registration/check-2: 404 for a talk that exists (two steps)

- **Problem:** the harness registered for an accepted presentation (POST, we said 404 "workshop not found"), and,
  once that was 409, read its list (GET, 404 again). The team's decision was 404 for any non-workshop. Two deploys,
  because a transcript only shows the first wrong step. Bled ~7 points over ~10 ticks.
- **Change:** POST for an existing non-workshop talk is 409 (`03e531c`), GET is `200 []` (`699f186`); a talk that
  doesn't exist stays 404. Tests and the decisions doc updated.
- **Evidence:** the harness's sequences replayed locally; gate green; check-2 OK from tick 89.
- **Pushed:** `03e531c`, `699f186` (functional fixes while the check was red).

## 9. Suites that find this kind of error before the harness does

- **Problem:** three harness failures today (co-speakers #1/#2, workshop #2) were the same kind: a talk that exists,
  of an unusual kind or state, answering 404 on one endpoint; our per-feature suites only covered the combinations
  someone thought of, and a transcript only shows the first wrong step.
- **Change:** `consistency.py`: 12 talk endpoints x 4 formats x 5 statuses + a missing talk, each call on a fresh
  talk; an existing talk never 404 (except harness-confirmed sub-resource 404s), a missing talk always 404, no 5xx.
  `transcripts.py`: every harness transcript becomes a Hurl replay in `workflow/hurl/harness/` (accepted steps keep
  their status, the rejected step must change). Both run in `check.sh`; `watch.sh` saves a snapshot on every
  harness change so new transcripts are harvested.
- **Evidence:** on the pre-fix `675d69b` the matrix finds 30 violations, including both workshop bugs; on `699f186`
  it's consistent. The 9 replays fail on exactly the version that had their bug (`b7c525a`: co-speakers;
  `675d69b`: workshop) and pass now. `check.sh`: 31 Hurl files + matrix + races green.
- **Pushed:** see git log (tooling; waits for a teammate's push, as the newest commit on main is mine).

## 18. Every push to main deployed, even when nothing live changed

- **Problem:** docs, workflow, rules and test-only pushes each ran the tests, built an image and switched the
  container to a new revision: Actions minutes for nothing, and a switchover in which requests can fail (tick 36).
- **Change:** `[no deploy]` in the pushed commit's message still runs the tests but skips the image and the
  rollout (at first it skipped every job; the tests now run so `main` stays checked); it wins over `[fast]`;
  a manual run always deploys. `AGENTS.md` now says which changes deploy (fixes, features) and which don't.
- **Evidence:** `actionlint` clean; job conditions checked for normal, `[fast]`, `[no deploy]`, both, and manual runs.
- **Deployed:** this commit is itself `[no deploy]`.

## 10. Every talk read took two database connections (R1)

- **Problem:** under load (60 users) the pool of 5 was always full, up to 66 requests waiting; requests spent 5.6x
  longer waiting for a connection than using one, so `GET /speakers/{id}` (2 ms of work) took ~100 ms. Every talk
  read took 2 connections: the talks, then their co-speakers in a separate query.
- **Change:** `TalkDao` reads co-speakers in the talk query (`array_agg ... ORDER BY seq`); the separate lookups in
  `ConferenceService` are gone. 2 files, +12/-14.
- **Evidence:** 1 connection per talk read instead of 2; `GET /talks` holds it 6.06 ms instead of 7.07 ms (same
  data). Median of 3 load runs: 959 -> 1024 req/s, cheap endpoints' p95 -17 %, lists unchanged within noise.
  `check.sh`: 58 Hurl files + matrix + races green; `compare.sh` equivalent to `c48a6e4`. Also fixed `perf.sh`
  syncing against :8080 whatever it was testing.
- **Pushed:** `[no deploy]`; goes live with the next deploy.

## 22. Programme scheduling (drip feature `scheduling`)

- **Problem:** new feature at tick 100: accepted talks into a fixed grid (R1–R3 × S1–S5), workshops only in S4,
  one talk per cell, a talk once, nobody in two rooms of a slot, rules checked only when scheduling, safe under
  concurrent committee members.
- **Change:** `PUT`/`GET`/`DELETE /programme/{room}/{slot}` (`ProgrammeController`, `ProgrammeService`,
  `ProgrammeDao`); table `programme` seeded with the 15 cells, unique index on `talk_id`; a PUT locks the three
  cells of its slot (`FOR UPDATE`) before checking occupancy and people. Decisions in
  `workflow/decisions/scheduling.md` (409 for an existing talk that can't go there, 404 only for what doesn't exist,
  empty cell = 200 with `talkId: null`, DELETE 204). Gate: 6 `scheduling-*.hurl`, race checks, a matrix column,
  all local-only (the grid is shared with the harness in production; new rule in `.claude/rules/testing.md`).
- **Evidence:** `SchedulingSpecTest` 26 black-box tests (spec-test-writer recipe, written without `src/main`); it
  caught a real bug before any push: `getLong` + `wasNull` after another column made every empty cell look taken.
  JUnit 111 green; `check.sh` ALL GREEN on :8080 and the linux/amd64 image (matrix 21 × 13 consistent; races:
  10 talks → 1 cell, 1 talk → 5 slots, 1 speaker → 3 rooms, each exactly one 200, no 5xx); `compare.sh`
  EQUIVALENT to `c48a6e4`.
- **Pushed:** see below.

## 23. Timeouts on the first harness burst after a deploy (ticks 36, 100, 103)

- **Problem:** at tick 100 nearly every check failed on its first request (`POST /speakers`: no response), and five
  more at tick 103. Each was the harness's first burst on a revision started a minute before: p95 3.9 s and 2.6 s,
  slowest 4.8 s and 5.3 s, against p95 0.3-0.5 s once warm (13:12-13:16). Three rollouts in four minutes (13:05-13:09),
  none of which changed the service. About 25 points lost.
- **Change:** a `warmup` job after `deploy` (`continue-on-error`, so it never fails the deploy) runs
  `workflow/bin/warmup.py`: six rounds of ~150 parallel requests along the harness's paths, only reads and writes
  rejected before writing. With `[no deploy]` (on main since #5), CI/docs/tooling merges no longer roll out.
- **Evidence:** all six tables byte-identical before and after a warm-up (924 requests: 446 GET 200, 420 POST/PUT
  400, 60 DELETE 404). At 0.5 CPU, a burst of ~230 requests (every Hurl suite) on a cold instance: p50 91-94 ms, p95
  ~308 ms, max 1.1-1.4 s, and the "< 1 s" speed assertions failed in 2 of 3 runs; after a warm-up: p50 7-8 ms, p95
  102-144 ms, max 0.4-0.6 s, all green.
- **Deployed:** pending merge.

## 22. Node 20 deprecation warnings; ubuntu-latest moving to Ubuntu 26.04

- **Problem:** `actions/cache@v4`, `docker/setup-buildx-action@v3` and `docker/build-push-action@v6` run on Node 20
  (deprecated, forced onto Node 24); `ubuntu-latest` becomes Ubuntu 26.04 from 2026-10-19, where Hurl's .deb doesn't
  install (seen on the fork).
- **Change:** `actions/cache@v5`, `setup-buildx-action@v4`, `build-push-action@v7` (each declares `node24`; every
  input we use exists in v7); every job in `deploy.yml` and `logs.yml` on `ubuntu-24.04`.
- **Evidence:** `actionlint` clean; action runtimes read from each tag's `action.yml`.
- **Deployed:** pending merge; the image job's new action versions first run on the next deploying push.

## 25. Scheduling checks 1–5 failed: empty cell and unknown talk

- **Problem:** after the claim, 5 of 6 scheduling checks were FAULTY. Checks 2–5 stopped at `GET` of an empty
  cell (we said 200 `"talkId": null`); check 1 stopped at `PUT` with a `talkId` that doesn't exist (we said 404,
  right after the harness accepted 404 for slot `S6` and 400 for `"talkId": true`).
- **Change:** empty cell `GET` → 404; unknown `talkId` in the body → 400. `ProgrammeService`, decisions, spec
  test, Hurl suites, matrix (`MISSING_EXPECT`), `race.sh` `get()` tolerates a 404 body.
- **Evidence:** the 5 harness transcripts as replays (`workflow/hurl/harness/scheduling-check-*-t119.hurl`) pass;
  `check.sh` ALL GREEN locally; `./mvnw verify` 111 green. Pushed with `[fast]` (losing points).
- **Pushed:** see below.

## 26. The warm-up never ran after a fast-lane deploy

- **Problem:** `warmup` had no `if:`, so GitHub's default applied, which also requires `deploy`'s own needs to have
  succeeded; in the fast lane `quality` is skipped, so `warmup` was skipped too. Seen in production: `fa7afff`
  ([fast]) went live at 13:32:28 with no warm-up traffic after it.
- **Change:** `warmup` runs `if: !cancelled() && needs.deploy.result == 'success'`: after every deploy that went out,
  normal or fast, and never when nothing deployed.
- **Evidence:** `actionlint` clean; the request log after `fa7afff` (no warm-up burst).
- **Deployed:** CI only, `[no deploy]`; the next deploy is its first run.
