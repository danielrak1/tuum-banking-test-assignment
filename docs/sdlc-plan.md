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
| 3. Build | ⏭ Next | code, `CLAUDE.md`, `.claude/skills/`, `.claude/agents/` |
| 4. Test | ⬜ | tests, JaCoCo gate, `docs/test-plan.md`, contract check, k6 load test |
| 5. Deploy | ⬜ | hooks, PR review loop, `.github/workflows/ci.yml` |
| 6. Maintain | ⬜ | `docs/retro.md`, final README |

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
| Skill (enabled) | `/code-review`, `/security-review`, `/simplify`, `engineering:deploy-checklist` | Deploy |
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
  5. events. Also: give the outbox its own explicitly configured `JsonMapper`, so a
     `spring.jackson.*` change to the HTTP mapper can't silently change the §5 event format
     (task 2 review, finding H).
  6. errors. Includes design.md §3 rule 4: map Jackson parse errors by field path to that field's
     code (e.g. `"amount": "abc"` → `INVALID_AMOUNT`), with a one-entry `errors[]`. Until then every
     parse error is `VALIDATION_FAILED` (task 2 review, finding F).
  7. multi-stage Dockerfile, and docker-compose with healthchecks. RabbitMQ's `guest` user only
     works from loopback, so the app container can't use it: set `RABBITMQ_DEFAULT_USER`/`RABBITMQ_DEFAULT_PASS`
     on the broker, and give the app env overrides (`SPRING_DATASOURCE_URL`, `SPRING_RABBITMQ_HOST`,
     `SPRING_RABBITMQ_USERNAME`, `SPRING_RABBITMQ_PASSWORD`) pointing at the compose services.

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
- **Throughput:** a k6 script, run through its Docker image, measures TPS and p95 latency. Results go in the README.
- Write the **`verify` skill**, which chains all of the above.

## Stage 5: Deploy
- **Hooks as gates** in `.claude/settings.json`:
  1. After every `.java` edit, compile (PostToolUse).
  2. Before `git commit`, run `./gradlew check`; block the commit if it fails (PreToolUse).
  3. Block edits to Flyway migrations that already exist (PreToolUse).
  4. *(Optional)* a Stop hook that reminds about CLAUDE.md.
  - Trigger each gate once on purpose to see it work.
- **PR loop:** feature branch → `/commit-push-pr`, then review with `/code-review`, `pr-review-toolkit`, `banking-reviewer`, `spec-checker` and `/security-review`, then fix and merge.
- **CI:** GitHub Actions with JDK 25: `./gradlew check`, the JaCoCo report, `docker build`, and a compose smoke test plus the contract check. Finish with an `engineering:deploy-checklist` pass.

## Stage 6: Maintain
- Run `session-report` / `explain-usage` to see where the effort went.
- Write `docs/retro.md` covering:
  - time per stage, coverage %, TPS
  - review findings by category
  - how often the hooks blocked
  - where Claude needed steering
  - what to change next time
- Run `/revise-claude-md`.
- Finish the README with every deliverable the PDF asks for: build and run, key choices, TPS, horizontal scaling, AI usage.

## Definition of done
- `docker compose up --build` on a clean clone starts everything, with the schema created automatically.
- `./gradlew check` is green, with coverage ≥ 80% enforced.
- The contract check passes. The concurrency test and the "events never lost" test pass.
- Events can be seen in the RabbitMQ UI (localhost:15672).
- The TPS figure is recorded. CI is green on the final PR. Every hook has been seen blocking.
- The README and `docs/retro.md` are complete.
