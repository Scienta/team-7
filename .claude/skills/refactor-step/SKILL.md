---
name: refactor-step
description: One iteration of the refactor loop - take the next open item from workflow/refactor-backlog.md, make one small change on main, prove it with the gate (Hurl, races, behaviour equivalence, load test), commit and push it right away if green, and report Problem / Change / Evidence / Pushed. Use with /loop, or when asked for "the next refactor step".
---

# One refactor iteration

You are improving concurrency, response times and code structure of the CFP service in small, proven steps.
Each iteration does **one** small slice of a backlog item, or nothing, and pushes it: `origin/main` is the only
code that matters, and teammates push to it too.

## Hard rules

- Work on `main`: `git fetch` and build on `origin/main` first; push as soon as the gate is green (a push deploys;
  don't wait for earlier deploys). Never force-push. Never touch `.fagdag/`, `.github/`, the harness token, or
  anything outside this repo.
- Never pick an item tagged **needs-human**. Never change API behaviour in a **refactor**, **perf** or
  **observability** item: `compare.sh` must say EQUIVALENT.
- Tests are the spec: never edit a Hurl suite, `race.sh`, `load.js` invariants or JUnit expectations to make a
  change pass. Adding tests is fine.
- No new dependencies, no `schema.sql` changes, no Java/Spring upgrades. The Hikari pool stays at most 5.
- Small steps: aim for under ~200 changed lines. If an item is bigger, do a coherent first slice, commit it, and
  leave the rest of the item open with a note.
- Don't overwrite someone else's work: if the working tree has changes you didn't make this iteration, stop and
  tell the user.

## Steps

1. **Harness first.** Run `workflow/bin/feedback.sh`. If any check isn't OK or the service is down, don't refactor:
   report it to the user and end the iteration. Production beats tidiness.
2. **Pick.** The first `[ ]` item under "Open" in `workflow/refactor-backlog.md`. None left: say the backlog is done
   and stop the loop.
3. **Measure before** (perf items): run `workflow/bin/perf.sh` (and anything else the item asks for) and write the
   finding into the item before changing code.
4. **Change** the code, plus tests for anything new.
5. **Gate**, in this order, each one green before the next (everything builds in Docker with the deployed JDK; JUnit
   runs in CI after the deploy):
   1. `workflow/bin/local.sh up`
   2. `workflow/bin/check.sh` (Hurl suites + race checks): `ALL GREEN`
   3. `workflow/bin/compare.sh`: `EQUIVALENT to <HEAD>`
   4. `workflow/bin/perf.sh`: `PERF GREEN` (no unexpected status, invariants hold, no p95 regression). Perf items
      must also show the improvement they promised; then refresh the baseline with
      `workflow/bin/perf.sh --save-baseline` and commit `workflow/perf/baseline.json` with the change. Other items
      never refresh the baseline.
   A red gate: fix and rerun from the first gate. After two failed fixes, revert your changes
   (`git restore --staged --worktree` on the files you touched, delete files you created), mark the item `[!]` with
   the reason and what you learned, and commit only the backlog.
6. **Commit and push** on `main`: `perf:`/`refactor:`/`chore:` subject, a body with the why, the gate summary lines
   and the before/after numbers, and the `Co-Authored-By` trailer. In the same commit, mark the item `[x]` and add
   the iteration to `workflow/iterations.md`. `git pull --rebase` if the push is rejected, rerun the gate if that
   brought in code changes, then push. Start `gh run watch <id> --exit-status` in the background.
7. **Report** to the user in exactly this shape, then end the iteration:
   - **Problem:** what was wrong, with the number or request that shows it
   - **Change:** what changed, files and lines
   - **Evidence:** the gate results and before/after numbers
   - **Pushed:** the commit, and the deploy result once known (fill it into `workflow/iterations.md` later)
