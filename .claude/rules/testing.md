# Testing and feedback loop

Run these before saying a change works. Paste the summary lines as evidence; never claim "done" without them.

- `workflow/bin/local.sh up`: builds (no tests) and (re)starts the app on JDK 11 with the Compose Postgres,
  waits for `/health`. Always JDK 11: newer JDKs format `ratedAtDisplay` differently than the deployed image.
  `local.sh logs` tails `.local/app.log`.
- `workflow/bin/local.sh image`: deploy rehearsal before pushing. Builds the root `Dockerfile` as `deploy.yml`
  does (linux/amd64, `GIT_SHA`), runs it on :8081, requires `/health` to report that SHA. Then `check.sh http://localhost:8081`.
  Do this before any push that touches `Dockerfile`, `pom.xml` or `schema.sql`.
- `workflow/bin/check.sh [base-url]`: published `examples/*.hurl` + our `workflow/hurl/*.hurl` + the parallel
  checks in `workflow/bin/race.sh`. Default is local; pass `https://fagsem-h2026-team-7.scienta.cloud` after a deploy.
  `check.sh <base-url> <file.hurl>` runs one suite.
- `./mvnw package` (with `JAVA_HOME=$(/usr/libexec/java_home -v 11)`): JUnit tests, needs Docker.
- `workflow/bin/feedback.sh`: harness score, every check's status, what changed since the last run, and the
  latest timeline entry (failing request + transcript) for each non-OK check. Rate-limited: don't loop it faster
  than once a minute.
- `workflow/bin/watch.sh [seconds]`: run it in the background for the whole session. It exits when anything on the
  harness changes (a check's status, a feature released or claimed, the service down); then run `feedback.sh`,
  tell the user what changed, and start the watcher again.

## Every change, in this order

1. `feedback.sh` before starting: know what the harness says about the deployed code now.
2. A test that fails first: a Hurl suite in `workflow/hurl/` for behaviour, JUnit for units and races.
3. `./mvnw package` and `local.sh up` + `check.sh`, all green. Paste the summary lines.
4. Changed `Dockerfile`, `pom.xml` or `schema.sql`? Also `local.sh image` + `check.sh http://localhost:8081`.
5. After the person deploys: `check.sh https://fagsem-h2026-team-7.scienta.cloud`, then `feedback.sh` (and again a
   tick or two later) to confirm nothing went red.

Performance is behaviour too: list endpoints have a `duration` budget in `workflow/hurl/talks-list-speed.hurl`.
Changing how talks are read? Compare responses with the previous build on the same data (old build on :8081 via
`local.sh image` from the old commit, new on :8080) before trusting a refactor.

## Writing Hurl suites

- One file per rule (`workflow/hurl/<feature>-<rule>.hurl`), so one failure doesn't hide the others; Hurl stops a
  file at its first failing request.
- Fresh data per run (`{{newUuid}}` in emails), scope assertions to your own speaker/talk so other data doesn't matter.
- Where the spec is silent on a status code, assert the range (`status >= 400`, `status < 500`) and record the
  team's decision in the spec notes; where it's ours to pin, pin it.
- Spec rules come from `workflow/specs/<feature>.md` (fetched from the harness). A feature request isn't a spec:
  write the edge cases down before implementing.
