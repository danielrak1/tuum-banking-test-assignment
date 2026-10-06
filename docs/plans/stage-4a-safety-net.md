# Stage 4, part A: Safety net (`test/safety-net`)

> **Status:** in progress. Planned 2026-10-06 and approved with one addition (an optional event check in the contract check).

## Context
Stage 4 (`docs/sdlc-plan.md`) turns the tests from Stage 3 into a safety net that can be shown:
- a test plan that traces every PDF error and every `intent.md` success criterion to the tests that
  prove it;
- a contract check that runs the PDF's requests against the real compose stack;
- a `verify` skill that runs the whole local loop in one command.

There are also two Stage 4 carry-overs:
- `@Size` counts UTF-16 units, so emoji are over-rejected;
- the drifted private helper copies in `AccountApiIT` and `ProtocolErrorsIT`.

**Inventory (Explore agent):** every PDF error already has tests, and so do criteria 3 and 4.
- No automated test covers criterion 5 (compose). The contract check under `verify` closes that.
- No test sends emoji at a size limit.

**Out of scope (part B):** k6, the existence-check query, balance overflow.

## Decisions (approved)
1. **`@Size` → count code points (recommended).**
   - design.md §2 says "characters", and Postgres `varchar(n)` counts code points: I checked that
     `length()` of 3 emoji is 3. JSON Schema `maxLength` (the OpenAPI doc) also counts code points.
   - `@Size` counts UTF-16 units, so today 64 emoji in `customerId` (128 units) are rejected, even
     though the DB would store them.
   - The fix:
     - a new `@MaxCodePoints(n)` constraint on `customerId` (64) and `description` (255), replacing
       `@Size`;
     - `@Schema(maxLength = n)`, so the OpenAPI doc keeps the limit `@Size` used to give it;
     - it isn't in `ApiExceptionHandler.FIELD_CODES`, so it maps to `VALIDATION_FAILED`, as today.
   - The alternative, documenting "UTF-16 units", would make the API stricter than its storage for
     no reason.
2. **The contract check targets a running stack.**
   - `scripts/contract-check.sh [BASE_URL]` (default `http://localhost:8080`), written in bash with
     curl and jq (both standard on macOS and on GitHub runners).
   - The verify skill, or CI in Stage 5, owns `up --build --wait` and `down`, so the script can
     also run against the dev stack.
   - It checks status and `code` on every case, plus the PDF's output fields on the success paths.
   - It exits 1 on any mismatch, and 2 if the stack isn't reachable.
3. **`verify` runs on an isolated stack.**
   - It uses project `banking-verify` on other host ports, then `down -v`. Every run starts clean,
     like a fresh clone, and it doesn't touch the dev stack.
   - This needs the compose host ports to be overridable, with the current values as defaults:
     `"${APP_PORT:-8080}:8080"`, and the same for Postgres, AMQP and the management UI.
   - A plain `docker compose up` is unchanged.
4. **The helper move doesn't change behaviour.**
   - The shared helpers move to `BankingApi` and `BankingEvents`, and both classes use them.
   - No assertion gets weaker. Where the copies differ, the stricter one wins:
     - `expectProblem` gains `ProtocolErrorsIT`'s non-blank-body check;
     - `assertSingleError` becomes `assertErrors`;
     - `ProtocolErrorsIT` takes the shared `JSON`, which sets `STRIP_TRAILING_BIGDECIMAL_ZEROES`
       explicitly.
   - The test count stays the same.

## Approach

### 1. Helpers (`src/test/.../BankingApi.java`, `BankingEvents.java`)
- **New in `BankingApi`:**
  - `postAccountRaw(String json)` and `getAccountRaw(String id)`;
  - `assertJsonContentType`;
  - `uniqueCustomerId()`.
- **New in `BankingEvents`:** `assertNoEventMentions(marker)`. It is copied three times today:
  `AccountApiIT`, `ProtocolErrorsIT` and `TransactionApiIT`.
- **`expectProblem`:** gains the non-blank-body check.
- **`AccountApiIT` and `ProtocolErrorsIT`:** delete their private `JSON`, `expectProblem`,
  `errors`, `assertSingleError`, `fieldNames`, `createAccount` (Protocol), `get` and `post`
  (Account), and use the shared ones. `TransactionApiIT` uses the shared `assertNoEventMentions`
  and `assertJsonContentType`.
- Class-specific fixtures stay where they are: `accountWith100Eur`, `assertNotAcceptable`,
  `accountCreatedEvent`, and so on.
- The other duplicates the inventory found (`assertMoney`/`assertTransaction` in `EventDeliveryIT`)
  are left as they are and noted in the test plan.

### 2. `@MaxCodePoints` (`com.danielrak.banking.api`)
- **The constraint:** `MaxCodePoints` plus `MaxCodePointsValidator`, using
  `s.codePointCount(0, s.length()) <= max`; null is valid. It replaces `@Size` in
  `CreateAccountRequest` and `CreateTransactionRequest`, and gets `@Schema(maxLength = …)`.
- **design.md §2:** the `customerId` and `description` rows say "Unicode code points, the way
  Postgres counts".
- **test-writer** (black-box) adds these boundary cases:
  - 64 emoji `customerId` → 201, stored unchanged;
  - 65 emoji → 400 `VALIDATION_FAILED`;
  - 255 emoji `description` → 201, stored unchanged;
  - 256 emoji → 400 `VALIDATION_FAILED`, and nothing posted.

  The ASCII boundary tests stay. `OpenApiIT` checks the `maxLength` of both fields.
- I show test-writer's findings and wait before committing.

### 3. `docs/test-plan.md` (structured with `engineering:testing-strategy`)
1. **Scope and sources:** the PDF, `intent.md` and design.md §2–§6.
2. **Test levels:**
   - unit (validators, `EventJson`, `Backoff`, `FailureLog`, `ApiExceptionHandlerTest`);
   - integration (`*IT` on Testcontainers);
   - convention (`NotFoundCodeConventionTest`);
   - contract (`contract-check.sh`);
   - load (k6, part B);
   - manual checks.

   For each: what it proves, how fast it is, and when it runs.
3. **Traceability, PDF errors:** each PDF error, its §3 rows, then the IT methods and contract-check
   case IDs that prove it.
4. **PDF behaviours:** the outputs, IN/OUT balance changes, balances created per currency, and the
   events for every insert and update.
5. **Success criteria 1–6:** what proves each one. Criterion 5 is proved by the contract check
   through `verify`.
6. **Coverage targets:** the JaCoCo gate (lines and branches ≥ 0.80), today's numbers, and the
   exclusions.
7. **Known gaps, accepted:**
   - Some rejection tests don't assert "nothing published". Every PDF error has at least one test
     that does.
   - The remaining test-helper duplicates.
   - The 406 body.
8. **How to run:** one class, `./gradlew check`, `verify`.

### 4. `scripts/contract-check.sh`
- **Pre-check:** `GET /actuator/health` must be UP (exit 2 if not).
- **Cases** (IDs C01…), each printing `PASS` or `FAIL` with expected vs actual:
  - **Create account:**
    - EUR+USD → 201, with `accountId`, `customerId` and balances 0.00 for each currency;
    - JPY → 400 `INVALID_CURRENCY`.
  - **Get account:**
    - → 200, with the same fields;
    - unknown → 404 `ACCOUNT_NOT_FOUND`;
    - malformed → 400 `ACCOUNT_NOT_FOUND`.
  - **Create transaction:**
    - IN 100.00 → 201, with every PDF output field and `balanceAfter` 100.00;
    - OUT 30.50 → `balanceAfter` 69.50;
    - JPY → 400 `INVALID_CURRENCY`; GBP not held → 422 `INVALID_CURRENCY`;
    - bad direction → 400 `INVALID_DIRECTION`;
    - negative amount → 400 `INVALID_AMOUNT`;
    - OUT over the balance → 422 `INSUFFICIENT_FUNDS`;
    - unknown account → 404 `ACCOUNT_MISSING`; malformed → 400 `ACCOUNT_MISSING`;
    - no description → 400 `DESCRIPTION_MISSING`.
  - **After the transactions:** GET account shows EUR 69.50 (the PDF's IN/OUT requirement).
  - **Get transactions:**
    - → 200, two items in post order, with the PDF fields;
    - unknown → 404 `INVALID_ACCOUNT`; malformed → 400 `INVALID_ACCOUNT`.
- **Event case (optional second argument: the RabbitMQ management URL, as banking/banking):**
  - It peeks `banking.events.all` through the management API (`ackmode=ack_requeue_true`, with
    `count` = the queue depth, so nothing is consumed). It retries for up to ~10 s, because
    publishing is asynchronous.
  - It checks the test account's events in order: `account.created`, `balance.created` ×2, then
    `transaction.created` + `balance.updated` for each successful transaction (×2).
  - Without the argument, it prints `SKIPPED (no mgmt URL)`, which counts as neither pass nor fail.
- **Output:** a summary `N passed, M failed, K skipped`.

### 5. `verify` skill (`.claude/skills/verify/SKILL.md` + `verify.sh`)
- **`verify.sh` runs four steps:**
  1. `./gradlew check`;
  2. `docker compose -p banking-verify up --build --wait`, with ports 18080, 15432, 15672 → 25672
     and 5672 → 25673;
  3. `scripts/contract-check.sh http://localhost:18080 http://localhost:25672`, so the event
     case runs;
  4. `down -v`, from a `trap`, so it always runs.
- **When a step fails:** the steps after it are skipped (and reported as skipped), compose logs are
  saved to a file before `down`, and the exit code is non-zero.
- **Summary:** one table at the end, with step, result and duration. On failure it adds the coverage
  numbers and the report or log paths.
- **`SKILL.md`:** when to use it (before a commit or PR), how to run it, how to read the summary,
  and what to do on each kind of failure.

### 6. Docs
- **sdlc-plan:** the Stage 4 row shows part A done; the two carry-overs are done.
- **CLAUDE.md:** a `verify` line under Commands.
- **This plan:** copied to `docs/plans/stage-4a-safety-net.md`, with an outcome section.

## Files
- **New:**
  - `docs/test-plan.md`, `scripts/contract-check.sh`;
  - `.claude/skills/verify/SKILL.md`, `.claude/skills/verify/verify.sh`;
  - `api/MaxCodePoints.java`, `api/MaxCodePointsValidator.java`;
  - `docs/plans/stage-4a-safety-net.md`.
- **Changed:**
  - `BankingApi`, `BankingEvents`, `AccountApiIT`, `ProtocolErrorsIT`, `TransactionApiIT`, `OpenApiIT`;
  - `CreateAccountRequest`, `CreateTransactionRequest`;
  - `docker-compose.yml` (port defaults only);
  - `docs/design.md`, `docs/sdlc-plan.md`, `CLAUDE.md`.

## Delivery and commits
1. Commit this plan.
2. The helper move → `./gradlew check` (same count, green) → commit.
3. `@MaxCodePoints`, design.md §2 and the test-writer tests → **show test-writer's findings and
   wait** → commit.
4. `docs/test-plan.md` → commit.
5. The contract check, the compose port defaults and the verify skill → run `verify` end to end →
   commit.
6. `/code-review low` on `scripts/contract-check.sh` and `.claude/skills/verify/*` only → **show
   its findings and wait** → fix → commit.
7. The sdlc-plan, CLAUDE.md and plan outcome → push and open the PR.

## Risks
- **Port clash:** another service on 18080, 15432, 25672 or 25673 would block `verify`. It fails
  fast, with the compose error in its summary. The ports can be overridden through env.
- **Run time:** `verify` takes about `check` (~2 min) plus the image build plus ~20 s for the stack.
- **The helper move** might hide a weakened assertion. Mitigation: the diff only removes
  duplicates, every call site keeps its arguments, and the test count is checked.

## Verification
- After each step, `./gradlew check` is green: the same test count after the helper move, plus the
  new boundary tests.
- `verify.sh` passes end to end with the dev stack running at the same time.
- **Negative run:** the contract check against a stack that has been changed on purpose must fail
  with a non-zero exit. Change the expected code of one case in a scratch copy and run it against
  the stack, so the real script is never touched. An unreachable `BASE_URL` → exit 2.
