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
- **Deployed:** see below.

## 3. The local loop assumed JDK 11; the app now runs on JDK 25

- **Problem:** after the teammate's upgrade to JDK 25 / Spring Boot 3, `local.sh` and `compare.sh` still built with
  a local JDK 11, so local results no longer matched what's deployed (and no JDK 25 is installed here).
- **Change:** `local.sh up|image|ref` and `compare.sh` build and run with the root `Dockerfile`, so the local app
  always has the deployed JDK; `workflow/specs/` dropped for the teammate's `specs/`; rules and the refactor-step
  skill now say: small slices, push right after a green gate, report Problem / Change / Evidence / Pushed.
- **Evidence:** `local.sh up` + `compare.sh` against `0f08036`: equivalent, 7.5 s with warm images; `check.sh` below.
- **Deployed:** tooling only, no service change.
