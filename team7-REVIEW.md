# Team 7: review of the day

Fagdag, 6 November 2026. Our own review of what we built, how we worked, and what we'd do differently.
Facts come from `git log` (60 commits on `main`), `workflow/iterations.md` (30 entries) and the harness feedback
at the end of the day. Times are local (CET). Sections marked **To discuss** are for the team to fill in.

## Where we ended up

- **11 features claimed, every check that has run is OK**: speakers, talks, ratings, co-speakers, acceptance,
  spring-boot-4, workshop-registration, scheduling, programme-export, and the change requests ratings-cr and
  submission-cap-cr. Three checks hadn't run again yet (ratings 2 and 4, co-speakers 3).
- **Service never reported down.** The points we lost all came from short bursts of timeouts: during deploys,
  and during our own test run against production (see Findings).
- **60 commits from 5 people**: Rafael 25 (9 merged PRs), Rune 19, Per 10, Christian 4, Anders 2 (setup).

## What we built, in order

| Time | What | Who | Commit |
|---|---|---|---|
| 09:25 | Repository, harness and logs set up | Anders | `c09c28d`, `01545b0` |
| 12:42 | Fixes to the base app: email validation, 64-bit reviewer ids, SQL injection in search | Per | `973812d` |
| 12:46–13:14 | JDK 25, Spring Boot 3.5, then Spring Boot 4.1 (drip feature `spring-boot-4`) | Per | `53addd1`, `6ff8b1d` |
| 12:54 | Acceptance state machine | Per | `befd5a7` |
| 13:01 | Co-speakers, then 404/400 fixes from the harness | Rafael | `17409d2`, `c8929ce` |
| 13:08–14:20 | Performance: talks with ratings in one query, stats and CSV without N+1, co-speakers in the same read | Rune, Per, Rafael | `d4cbc84`, `17e3675`, `84ef19a`, `cd3a4ef` |
| 12:20–12:31 | Request logging, a race fixed (409 not 500), refactoring, test suite 40 s → 25 s | Rafael | `419c1a1`, `37d3d42`, `2dc53dd`, `c58627a` |
| 13:41 | Workshop registration, then two status-code fixes | Per, Rune | `1efd870`, `03e531c`, `699f186` |
| 14:21 | Programme scheduling, then three `[fast]` fixes from the harness | Per, Rune | `76d6dd6`, `fa7afff`, `9a8b054`, `78030f5` |
| 14:49 | Programme export | Rafael | `bbf0d99`, `d0bcfd4` |
| 15:18 | Change request: ratings (trimmed average, 2 decimals, banker's rounding) | Per | `a40c8e4` |
| 15:34 | Change request: submission cap (co-speaking counts, rejected/withdrawn don't) | Per | `79ebd1e` |
| 15:52 | Talk lists built as JSON in Postgres | Rune | `127cedd` |
| 15:53 | Submission cap counted through indexes | Christian | `d51f208` |

## How we worked

### The pipeline and the deploy lanes

We started with "every push deploys", and changed it during the day:

- **Tests before deploy** (`d31f449`), then the image built while the tests run (`5dba3bc`, `075b9b9`).
- **`[no deploy]`** for docs, tooling and tests (`2a46473`, `7ea8bc5`). A deploy costs points (see Findings), so
  it's only for changes that alter the live service.
- **`[fast]`** for fixes to checks that are losing points right now (`36bd592`): no test gate, deploy as soon as the
  image builds. Used 4 times: three scheduling status codes the harness reported, and the talk-list rewrite
  (`127cedd`), a perf change rather than a failing check.
- **A warm-up job after every deploy** (`df7eea5`, `d68bbac`, `1f87b55`), because a fresh JVM timed out the
  harness's first burst. It had to be made gentler: the first version starved the harness's own checks.

### The local gate

Rune built most of it, early: `local.sh` (the deployed JDK in Docker), `check.sh` (Hurl suites, a consistency matrix
over every talk endpoint × format × status, 60+ race checks), `compare.sh` (behaviour equivalence against `HEAD`),
`dbdrift.sh` (local data in production's shape) and `perf.sh` (k6 against a baseline). Harness transcripts are
turned into Hurl replays (`transcripts.py`), so a check that failed once stays covered.

### Agents

- Everyone worked with Claude Code. Rules for agents live in `.claude/rules/` (testing, features) and `AGENTS.md`.
- A `spec-test-writer` agent writes black-box JUnit tests from the spec and our decisions file alone, never from
  `src/main`. It wrote the spec tests for programme export, the ratings change request and the submission cap.
  It also made mistakes: one test added a third co-speaker where the spec allows two, and one assumed a 204
  where the spec says 200. Its tests still need a human (or a second agent) to read them.
- Every ambiguous status code went into `workflow/decisions/<feature>.md` before the code. When the harness
  disagreed (several times for scheduling: 404 vs 409, 204 vs 404, 200 vs 409), the decision file records why we
  changed our mind.

## Findings

### 1. Timeouts cost points, and we caused them: deploys and our own load

Most of the afternoon's failures were requests that got **no response at all**, not wrong answers: ticks 145,
174, 191, 192, 194, 196–198 and 206, between 1 and 5 checks each. Every one recovered on the next tick without a
fix. Two causes, both ours:

- **Revision switches.** Ticks 145 (`programme-export`) and 206 (Rune's `[fast]` at 15:52) line up with a deploy.
  With features landing every 15–20 minutes, plus `[fast]` fixes, they added up.
- **Our own test run against production.** From 15:37 to 15:46 a `check.sh` ran against the live service, with
  its race checks and consistency matrix. Its parallel bursts landed on top of the harness's and queued in front
  of our single instance: requests the app answered in ~100 ms reached it seconds late (Per, `147c7ff`, ticks
  196–198). Ticks 191, 192 and 194 fall in the same window, and `submission-cap-cr` also deployed then, so either
  cause, or both, can explain them.

`[no deploy]`, batching fixes and the warm-up were the right moves for the first. For the second, `147c7ff` made
`check.sh` run only the Hurl suites, two at a time, against anything but localhost, and `perf.sh` refuse a remote
target. Lesson: **never load-test the service while it's being scored.**

### 2. The talk table grows every tick, and it shows

Production had 10 000+ talks by the afternoon, because every harness check creates its own. Queries that were
fine on seed data weren't later: the talk lists (fixed twice, `d4cbc84` and `127cedd`), and the submission cap's
`OR EXISTS` count, which scanned every talk under a row lock (1.6 ms → 0.13 ms with two indexed lookups, `d51f208`).
`dbdrift.sh` keeping local data in production's shape is what made this measurable.

### 3. We built the same feature twice, twice

Christian's session built `programme-export` and `ratings-cr` locally while Rafael and Per were already shipping
them. Both local versions were thrown away. The harness shows a release to everyone at once, and nothing told us
who had picked it up. A claim on the harness only shows *after* the work.

### 4. A wrong diagnosis caught before it shipped

At tick 194, five checks timed out together, just after `submission-cap-cr` went live. The cap's full-table count
looked like the cause, and a `[fast]` fix was ready. Measuring first (`EXPLAIN ANALYZE` on production-shaped data)
showed 1.6 ms, far too little to drain a pool of 5. The checks recovered on their own. The fix shipped later as an
ordinary `perf:` commit through the full gate instead. Measure before you `[fast]`. The likelier cause turned out
to be our own `check.sh` against production, running at that very moment (finding 1). Neither the harness
transcript nor the code could show that; the request log did, which is why Per found it and we didn't.

### 5. Tooling assumptions break on other machines

- `local.sh` didn't load images on Podman (buildx `docker-container` driver). Worked around locally with
  `DOCKER_BUILDKIT=0`; not committed, as only one of us runs Podman.
- `hurl` and `k6` weren't installed everywhere, and `check.sh` reports a missing tool as `FAILURES`.
- Two harness replays (`scheduling-check-5-t119`, `scheduling-check-6-t120`) have literal speaker ids, so they fail
  once those speakers reach the 3-talk cap in synced data. They need regenerating.
- The perf baseline was measured on 4 172 talks; production is past 11 000, so `perf.sh` no longer compares.

## What worked well

- Fixing what the harness reports quickly: scheduling's harness failures were fixed in three `[fast]` commits
  between 14:30 and 14:41 (`fa7afff`, `9a8b054`, `78030f5`).
- Writing decisions down before coding, and recording when the harness proved us wrong.
- Investing in the local gate early. It caught real problems (races, 404 for a talk that exists) before the
  harness did, and it made the late perf change safe to ship.
- Small commits, one problem each, with Problem / Change / Evidence / Pushed in `workflow/iterations.md`.

## What we'd do differently

- **Say who takes a feature the moment it's released**, before anyone starts. A message in the team chat or a
  line in a shared file is enough.
- **Batch deploys** where nothing is failing, and keep `[fast]` for a check that's losing points, after measuring.
- **Keep load tests local.** Against production, only light functional checks (`.claude/rules/testing.md`).
- **Re-baseline perf as the data grows**, and make the tools say "hurl not installed" rather than `FAILURES`.
- **Review agent-written tests** before trusting a red or green result from them.

## To discuss

- Did the rules in `.claude/rules/` and `AGENTS.md` help the agents, or did they get in the way? Which rule saved us,
  and which did we ignore?
- PRs (Rafael) vs straight to `main` (everyone else): did either cause problems, or catch any?
- Was the local gate worth its cost in time, compared with pushing and letting the harness tell us?
- What did the organisers' product owner clarify, and should we have asked sooner?
- How much did the timeouts cost in points, against the features we'd have missed by deploying less?

## Open items

- Regenerate the two replays with literal speaker ids (`transcripts.py`).
- Re-baseline `workflow/perf/baseline.json` on current data, ideally before and after `d51f208`.
- Confirm that `d51f208` deployed cleanly, and fill in iteration 30's "Pushed" line.
