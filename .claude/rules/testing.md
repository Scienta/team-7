# Testing, feedback and pushing

`origin/main` is the only code that matters, and teammates push to it too. Work in small slices: one problem per
commit, gate it, push it right away (no waiting for earlier deploys), verify the deploy in the background.

## The gate (seconds to a couple of minutes, no local JDK needed)

1. `git fetch` and build on top of `origin/main`.
2. `workflow/bin/local.sh up`: builds the working tree with the root `Dockerfile` (the deployed JDK) and runs it on
   :8080 against the Compose Postgres. `local.sh logs` follows its log.
3. `workflow/bin/check.sh`: published `examples/*.hurl` + our `workflow/hurl/*.hurl` + the parallel checks in
   `workflow/bin/race.sh`. Must end with `ALL GREEN`. `check.sh <url> <file.hurl>` runs one suite.
4. `workflow/bin/compare.sh`: runs `HEAD` on :8082 beside the working tree and diffs 31 reads and rejected writes.
   `EQUIVALENT` for refactors and perf work; for a fix, the only differences are the intended ones.
5. Perf work: `workflow/bin/perf.sh` (k6, 60 users, 1500 talks) against `workflow/perf/baseline.json`.
6. Changed `Dockerfile`, `pom.xml` or `schema.sql`: also `local.sh image` (linux/amd64, like `deploy.yml`) and
   `check.sh http://localhost:8081`.

JUnit runs in CI after each deploy (`tests` job in `deploy.yml`, not a gate). Check that run after pushing.

## After pushing

- `gh run watch <id> --exit-status` in the background, then `check.sh https://fagsem-h2026-team-7.scienta.cloud`
  (or the suite you touched) and `workflow/bin/feedback.sh`.
- `workflow/bin/watch.sh [seconds]` runs in the background all session and exits when anything on the harness
  changes (a check, a feature released or claimed, the service down): run `feedback.sh`, tell the user, restart it.

## Reporting

Every iteration, to the user and as an entry in `workflow/iterations.md`:
**Problem** (what was wrong, with the number or request that shows it), **Change** (files, lines), **Evidence**
(gate results, before/after), **Pushed** (commit, deploy result, what the deployed service shows).

## Writing Hurl suites

- One file per rule (`workflow/hurl/<feature>-<rule>.hurl`): Hurl stops a file at its first failing request.
- Fresh data per run (`{{newUuid}}`), assertions scoped to your own speaker/talk.
- Spec silent on a status code: assert the range (`status >= 400`, `status < 500`); otherwise pin it.
- Specs live in `specs/<feature>.md` (fetched from the harness). A feature request isn't a spec: write the edge
  cases down before implementing.
