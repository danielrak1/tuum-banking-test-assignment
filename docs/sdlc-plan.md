# SDLC Plan: running the AI-native SDLC playbook on this project

Practise run of the Claude Academy **AI-Native SDLC Playbook** (Plan → Design →
Build → Test → Deploy → Maintain) on the Tuum Software Engineer Test Assignment.
What we're building, and why, is in [`intent.md`](../intent.md). This file
covers *how* we work through the stages.

**Working mode:** coach mode. For each stage, Claude explains the concept,
discusses the decisions in chat, writes the artifact only once they're agreed,
then commits it.

## Status
| Stage | Status | Artifacts |
|---|---|---|
| 0. Setup | ✅ Done | JDK 25 (Temurin), Docker Desktop, gh CLI, IntelliJ + Claude Code plugin |
| 1. Plan | ✅ Done | `intent.md` |
| 2. Design | ✅ Done | `docs/design.md`, `docs/adr/*.md` |
| 3. Build | ✅ Done: tasks 1–6 cover all 7 build items (skeleton + schema PR #1, create/get account PR #2, transactions PR #3, events PR #4, errors PR #5, docker PR #6) | code, `CLAUDE.md`, `.claude/skills/add-endpoint`, `.claude/agents/test-writer.md`, `banking-reviewer.md`, `spec-checker.md` |
| 4. Test | ✅ Part A (safety net, PR #8): `docs/test-plan.md`, `scripts/contract-check.sh`, `verify` skill. Part B (throughput): `scripts/load-test.sh`, `docs/performance.md`, the existence fold. Balance overflow stays deferred | `docs/test-plan.md`, contract check, `verify`, k6 load test |
| 5. Deploy | 🟡 Hooks 1–3 (`.claude/settings.json`, `.claude/hooks/`), each seen blocking. CI (PR #11, follow-ups in PR #12): `verify.sh` plus a migration guard; the guard seen failing. PR review loop to do | hooks, PR review loop, `.github/workflows/ci.yml` |
| 6. Maintain | ✅ `session-report`, `docs/retro.md`, `/revise-claude-md` (PR #13), README (PR #14) | `docs/retro.md`, `README.md` |

## Environment
- Apple M5 Pro, 48 GB · Temurin 25.0.4.1 (arm64) · Docker 29.8.1 / Compose v5.5.1 · gh 2.102.0
- IntelliJ IDEA 2026.2.3 with the Claude Code plugin. The IDE provides compile
  errors and warnings, so the `jdtls-lsp` plugin is skipped.
- Gradle is not installed locally on purpose: the project uses the Gradle wrapper (`./gradlew`).
- Docker's socket is at `~/.docker/run/docker.sock`. If Testcontainers can't
  find it, enable "Allow the default Docker socket" in Docker Desktop, or
  configure it in `~/.testcontainers.properties`.

## Tooling map
| Kind | Name | Used in |
|---|---|---|
| Plugin (MCP) | context7: current Spring Boot 4 / MyBatis / Spring AMQP / springdoc docs | Design, Build |
| Plugin | feature-dev: explorer, architect and reviewer agents | Build |
| Plugin | commit-commands: `/commit`, `/commit-push-pr` | Build, Deploy |
| Plugin | claude-md-management: `/revise-claude-md` | Build, Maintain |
| Plugin | hookify: turns rules into hooks | Deploy |
| Plugin | pr-review-toolkit: test, silent-failure and type-design reviewers | Deploy |
| Plugin | security-guidance: security warnings on edits and commits | Build, Deploy |
| Plugin | session-report: usage report for the retro | Maintain |
| Skill (enabled) | `engineering:system-design`, `engineering:architecture` | Design |
| Skill (enabled) | `engineering:testing-strategy` | Test |
| Skill (enabled) | `/code-review`, `/security-review`, `/simplify` | Deploy |
| Skill (enabled) | `engineering:documentation` | Maintain |
| **Custom skill** | `add-endpoint`: the house recipe for adding an endpoint | Build |
| **Custom skill** | `verify`: the full local feedback loop in one command | Test |
| **Custom agent** | `spec-checker`: checks a design or diff against `intent.md` and the PDF | Design, every PR |
| **Custom agent** | `test-writer`: writes Testcontainers integration tests from acceptance criteria | Build, Test |
| **Custom agent** | `banking-reviewer`: reviews money handling, transaction boundaries, concurrency and events | Deploy |

## Stage 2: Design
1. **Ground in facts:** use context7 to look up current Spring Boot 4, MyBatis, Spring AMQP and springdoc APIs.
2. **Draft** `docs/design.md` with `engineering:system-design`. It covers:
   - API: `POST /accounts`, `GET /accounts/{id}`, `POST /accounts/{id}/transactions`, `GET /accounts/{id}/transactions`, plus Swagger UI
   - error mapping: RFC 9457 `ProblemDetail`, with 400 for validation, 404 for not found and 422 for insufficient funds
   - data model: `account`, `balance` with `UNIQUE(account_id, currency)` and `NUMERIC(19,2)`, `transaction` with `balance_after`
   - schema migrations: Flyway
   - event contract: a topic exchange `banking.events`
3. **ADRs** with `engineering:architecture`, one per real decision:
   - money handling
   - concurrency (atomic conditional update)
   - event delivery (outbox vs publish-after-commit), which success criterion 4 drives
   - event granularity
4. **Red-team:** two agents run in parallel. `spec-checker` looks for gaps against the intent and the PDF. A simplicity critic argues for the minimum design that still meets the success criteria.
5. **Review** each ADR in chat → **commit**.

## Stage 3: Build (deep)
- **Plan mode first**, for every task.
- **`CLAUDE.md`:** generated with `/init` once the skeleton exists, then edited to add:
  - build and test commands
  - package layout (`api / domain / persistence / messaging`)
  - the rules from the ADRs
  - the error contract
  - "Testcontainers, never H2"
  - "never edit an applied Flyway migration"
- **Skills:** write `add-endpoint` after the first endpoint, then use it for the other three.
- **Agents:** create `test-writer` and `banking-reviewer`. Once the skeleton and schema land, build the Account APIs and the Transaction APIs in parallel, each in its own git worktree, then merge.
- **Build order:**
  1. Gradle skeleton: Spring Boot 4.x, Java 25 toolchain, Gradle 9.1+, MyBatis, Flyway, AMQP, springdoc, Testcontainers, JaCoCo 0.8.14+
  2. schema
  3. create/get account
  4. transactions
  5. events ✅ (`docs/plans/task-4-events.md`):
     - outbox poller with an advisory lock and correlated confirms;
     - the `banking.events` topology;
     - its own event `JsonMapper` (`EventJson`, closing task 2 finding H);
     - `payload` changed to `json` (V2);
     - the event tests moved to the queue (closing the task 3 test-writer finding);
     - the criterion 4 test (`EventDeliveryIT`).
  6. errors ✅ (`docs/plans/task-5-errors.md`):
     - Jackson parse errors are mapped by field path to the field's own validation code
       (design.md §3 rule 4), with a one-entry `errors[]` (task 2 review, finding F);
     - strict request JSON: string amounts, duplicate keys (`VALIDATION_FAILED`) and scalar
       coercion into string fields are rejected;
     - the five `@Disabled("task 6: …")` tests are enabled.
     The balance-overflow 422 was out of task 5's scope. It is deferred to the Stage 4 carry-overs.
  7. docker ✅ (`docs/plans/task-6-docker.md`):
     - a multi-stage Dockerfile (Gradle wrapper on a JDK, `bootJar`, layered extraction, a non-root JRE);
     - compose runs the app after healthy Postgres and RabbitMQ, with its own RabbitMQ user (`guest`
       is loopback-only) and connection env vars;
     - Actuator health is app + DB only (the RabbitMQ indicator is off, ADR-0003), and the compose
       healthcheck polls it with a real HTTP GET;
     - springdoc shows the required fields and enums, with explicit operationIds, and `OpenApiIT`
       guards both.
     For the README: health serves as both liveness and readiness, and the host ports (5432, 5672,
     15672, 8080) must be free.

## Stage 4: Test (deep)
- Write `docs/test-plan.md` with `engineering:testing-strategy`. Every API error in the PDF maps to a test.
- **Feedback loop:**
  - `./gradlew check` runs Testcontainers (Postgres + RabbitMQ)
  - tests consume the queue to check event contents
  - the JaCoCo gate fails the build below 0.80
- Test types:
  - controller validation
  - service unit tests
  - full HTTP → DB → MQ integration tests
  - a **concurrency test** proving balances never go negative
  - a test proving **events are never lost**
- **Contract check** (`scripts/contract-check.sh`): runs every request and error case from the PDF against the running compose stack. This is our version of the playbook's "continuous evals".
- ✅ (part B) **Throughput:** a k6 script, run through its Docker image, measures TPS and p95 latency, plus
  the outbox lag under load (`scripts/load-test.sh`, `docs/plans/stage-4b-performance.md`). Results are in
  `docs/performance.md`, for the README.
- **Carried over from the task 3 review:**
  - ✅ (part A) Lengths count code points: `@MaxCodePoints` replaces `@Size` on `customerId` and
    `description`, with emoji boundary tests.
  - ✅ (part A) `AccountApiIT` and `ProtocolErrorsIT` use the shared `BankingApi`/`BankingEvents`
    helpers.
  - ✅ (part B) Throughput: fold `TransactionService.create`'s two existence checks (account, balance) into one
    `SELECT EXISTS …, EXISTS …`, and use `AccountMapper.exists` instead of mapping the whole account row.
- **Carried over from the post-build audit (banking-reviewer, spec-checker):**
  - Balance overflow (design.md §8): a 422 instead of a 500 for an `IN` that pushes a balance past
    `NUMERIC(19,2)`. The audit rated it Low and it stays deferred. When it is built, translate
    SQLSTATE 22003 at the boundary, not inside `@Transactional`: Postgres has already aborted the
    transaction. Don't add an upper bound to `applyDelta`'s `WHERE` either, because a 0-row `IN`
    would then fail as an `IllegalStateException`.
- ✅ (part A) Write the **`verify` skill**, which chains all of the above:
  `.claude/skills/verify/verify.sh` (`docs/plans/stage-4a-safety-net.md`).

## Stage 5: Deploy
- **Hooks as gates** in `.claude/settings.json`:
  1. ✅ After every `.java` edit, compile (PostToolUse).
  2. ✅ Before `git commit`, run `./gradlew check`; block the commit if it fails (PreToolUse).
  3. ✅ Block edits to Flyway migrations that already exist (PreToolUse).
  4. *(Optional, skipped)* a Stop hook that reminds about CLAUDE.md.
  - ✅ Trigger each gate once on purpose to see it work (`docs/retro-notes.md`, "Hooks").
  - Known gap: the file gates (1 and 3) see only Claude's Edit/Write/MultiEdit tools. A Bash edit
    (`sed -i`, a heredoc, `mv`, `rm`) passes both unchecked; gate 2 still catches a broken build at commit.
    ✅ CI part 2 closes it for migrations (PR #11).
- **PR loop:** feature branch → `/commit-push-pr`, then review with `/code-review`, `pr-review-toolkit`, `banking-reviewer`, `spec-checker` and `/security-review`, then fix and merge.
- ✅ **CI** (`.github/workflows/ci.yml`, PR #11): GitHub Actions with JDK 25: `./gradlew check`, the JaCoCo report, `docker build`, and a compose smoke test plus the contract check. The `engineering:deploy-checklist` pass is dropped: there is no deploy target.
  - ✅ Part 2: on PRs, fail on any change to a `V*.sql` other than an addition, compared with the PR's base
    branch (closes the hooks' Bash-edit gap for migrations). Job `migration-guard`; seen red on a V1 edit (run 37522190374),
    then reverted.

## Stage 6: Maintain
- ✅ Run `session-report` / `explain-usage` to see where the effort went.
- ✅ Write `docs/retro.md` covering:
  - time per stage, coverage %, TPS
  - review findings by category
  - how often the hooks blocked
  - where Claude needed steering
  - what to change next time
- ✅ Run `/revise-claude-md`.
- ✅ Finish the README with every deliverable the PDF asks for: build and run, key choices, TPS, horizontal scaling, AI usage.
  Future work to list there: idempotency keys, and a version field in `balance.updated` (design.md §9).
  Also note that health serves as both liveness and readiness, and that ports 5432, 5672, 15672 and 8080
  must be free.

## Definition of done
All met (2026-10-07). CI checks out a clean clone and runs `verify.sh`, which covers the first four items on every PR.
- ✅ `docker compose up --build` on a clean clone starts everything, with the schema created automatically.
- ✅ `./gradlew check` is green, with line and branch coverage ≥ 80% enforced.
- ✅ The contract check passes. The concurrency test and the "events never lost" test pass.
- ✅ Events can be seen in the RabbitMQ UI (localhost:15672).
- ✅ The TPS figure is recorded. CI is green on the final PR. Every hook has been seen working (gate 1 reports, it can't block).
- ✅ The README and `docs/retro.md` are complete.
- The repo is accessible to the assignment's reviewers (PDF, Handover): N/A, because this is a practice
  run and is not submitted.
