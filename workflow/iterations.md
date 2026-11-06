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
- **Deployed:** see below.

## 2. A bio of 500 emoji was "too long"

- **Problem:** the spec allows 500 characters; `@Size` counts UTF-16 units, so an emoji counts twice and 500 emoji
  were rejected with 400.
- **Change:** the bio is counted in code points, on register and on update (update allowed 1000, now 500 like
  register). 2 files, +11/-6.
- **Evidence:** 500 emoji: 400 → 201; `speakers-bio.hurl` green, all 17 Hurl files and the race checks green;
  equivalent to `d4cbc84` on 31 requests.
- **Deployed:** see below.
