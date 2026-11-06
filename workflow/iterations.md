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

## 4. No race checks for acceptance, co-speakers or duplicate registrations

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
