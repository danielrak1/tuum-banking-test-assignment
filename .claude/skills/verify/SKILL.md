---
name: verify
description: The full local feedback loop for this banking service in one command — ./gradlew test --rerun check (every test, never UP-TO-DATE, plus the JaCoCo gate), an isolated docker compose stack, the contract check (every PDF request and error, plus events) against it, then teardown — ending in one summary table. Use before a commit or PR that touches code, config, the Dockerfile or compose, and whenever the user asks to "verify" or run "everything".
---

# Verify

Runs `.claude/skills/verify/verify.sh` from the repo root. It takes about 2–3 minutes: the tests are most
of that, and the image build is cached after the first run. Exit codes: 0 if everything passed, 1 if a step
failed, 130 if it was interrupted (teardown still runs).

| Step | Proves | Log |
|---|---|---|
| `./gradlew test --rerun check` | every unit and integration test, coverage ≥ 80% lines and branches | `build/verify/check.log` |
| `compose up --build --wait` | a clean stack builds and becomes healthy, with Flyway migrating an empty DB (criterion 5) | `build/verify/up.log` |
| contract check | the PDF's requests and errors over real HTTP, and the account's events on the queue | `build/verify/contract-check.log` |
| `compose down -v` | always runs, even after a failure or Ctrl-C | `build/verify/compose.log` (stack logs) |

`--rerun` forces `:test` to run even when Gradle considers it UP-TO-DATE. Without it, a run with no
source changes reports "PASS 0s" and proves nothing. The coverage line is only printed when this step
ran in the same invocation.

The stack is the compose project `banking-verify`, on ports 18080 (API), 15432, 25673 and 25672
(RabbitMQ UI), with its own volumes, so it starts empty, like a fresh clone. The dev stack (`docker compose
up` on the default ports) can keep running next to it. Override the ports with `VERIFY_APP_PORT`,
`VERIFY_POSTGRES_PORT`, `VERIFY_AMQP_PORT` and `VERIFY_RABBITMQ_UI_PORT`.

## Run
```sh
.claude/skills/verify/verify.sh                # all four steps
.claude/skills/verify/verify.sh --skip-check   # only the stack and the contract check
```
Run it in the background (it takes minutes), then read the summary table at the end of its output.

## Report
Give the user the summary table as it is, plus:
- the coverage line;
- for a contract-check failure, every `FAIL` line with its expected-vs-actual line.

Say "VERIFY PASSED" only if the script printed it. Never re-run a failed step on its own and report
that as the result.

## When a step fails
- **`./gradlew test --rerun check`:** find the failing tests in `build/test-results/test/*.xml`, or the coverage
  violation at the end of `build/verify/check.log`. Fix the code or the test the way CLAUDE.md says,
  and never weaken an assertion to make it pass.
- **compose up:** read `build/verify/up.log`, then `build/verify/compose.log` for the app's startup
  error. "port is already allocated" means another process holds a verify port: override it, don't
  stop the user's containers. A failed healthcheck usually means the app didn't start (look for
  Flyway or connection errors in the app log).
- **contract check:** each `FAIL` line names the case (C01…C20, listed in `docs/test-plan.md`), and
  the line under it gives the expected and actual status, code or field. A failure here with a green
  `check` means the containerised app behaves differently from the test context. Suspect config: env
  vars in `docker-compose.yml`, `application.yml` defaults, or the image being out of date.
- **compose down:** remove the leftovers with `docker compose -p banking-verify down -v`.
