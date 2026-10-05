# Task 3: Transactions (`feat/transactions`)

> **Status:** built and tested 2026-10-05. `./gradlew check` is green: 122 tests, 2 skipped by
> design (decision 2); lines 0.99, branches 0.93. Not committed yet; the SKILL.md revision is
> proposed and awaiting review.

## Context
This is Stage 3, task 3 of 7 in `docs/sdlc-plan.md`. Task 2 (PR #2) shipped create/get account, the
ProblemDetail contract, `@NotFoundCode`, `StrictUuidBinding`, `OutboxWriter` (rows only) and the
`add-endpoint` skill. This task adds the last two design.md §2 endpoints:
`POST /accounts/{accountId}/transactions` and `GET /accounts/{accountId}/transactions`.
It also adds the §6 criterion 3 concurrency test (balances never go negative). Both endpoints
follow `.claude/skills/add-endpoint/SKILL.md`. This is the skill's first real use, so friction is
logged along the way and becomes a SKILL.md revision proposal at the end.

Scope:
- **Errors:** every transaction code in §3 except rule 4. Jackson parse errors (`"amount": "abc"`,
  a string amount) stay task 6; until then they give `VALIDATION_FAILED`.
- **Events:** outbox rows only. Task 5 adds the poller, so tests read `outbox_event` directly.

## Approach (all under `com.danielrak.banking`)

### 1. DTO and validation (`api`), skill step 1
`CreateTransactionRequest(amount, currency, direction, description)`. Each §3 row needs its own
code, including for a missing value, so every field gets a constraint whose violation maps to its code:

| Field | Constraint | Code |
|---|---|---|
| `currency` | `@SupportedCurrency String` (reused, already rejects null) | `INVALID_CURRENCY` |
| `direction` | new `@SupportedDirection String`: rejects null, `IN`/`OUT` case-sensitive | `INVALID_DIRECTION` |
| `amount` | new `@ValidAmount BigDecimal`: composes `@NotNull @Positive @Digits(integer=17, fraction=2)` with `@ReportAsSingleViolation` | `INVALID_AMOUNT` |
| `description` | new `@DescriptionPresent` (composes `@NotBlank`, `@ReportAsSingleViolation`), plus `@Size(max=255) @FreeText` | `DESCRIPTION_MISSING` / `VALIDATION_FAILED` |

- Why a custom `@DescriptionPresent` rather than mapping `@NotBlank`: `customerId` also uses
  `@NotBlank` and must stay `VALIDATION_FAILED`.
- `ApiExceptionHandler.codeFor` becomes a `switch` on `error.getCode()` (the constraint's simple
  name) over the four custom constraints, with `VALIDATION_FAILED` as the default.
- `Direction` enum `{IN, OUT}` goes in `domain`.
- `TransactionResponse(accountId, transactionId, amount, currency, direction, description, balanceAfter)`
  with `static from(Transaction)`.

### 2. Controller (`api`), skill step 2
- New `TransactionController`, `@RequestMapping("/accounts/{accountId}/transactions")`,
  `@Tag(name = "Transactions")`. Transactions are their own resource, so they don't go in `AccountController`.
- `POST`: `@NotFoundCode(ACCOUNT_MISSING)`. It returns **201 with no `Location`** (decision 1),
  so `ResponseEntity.status(CREATED).body(...)`. Its springdoc responses are 201, 400 (all four
  field codes, `ACCOUNT_MISSING` for a malformed ID, `VALIDATION_FAILED`), 404 `ACCOUNT_MISSING`,
  and 422 `INVALID_CURRENCY` / `INSUFFICIENT_FUNDS`.
- `GET`: `@NotFoundCode(INVALID_ACCOUNT)`. It returns 200 with `List<TransactionResponse>`, and its
  springdoc responses are 200, 400 and 404.
- The controller converts strings with `Currency.valueOf` and `Direction.valueOf`.

### 3. Service (`domain`), skill step 3
New `TransactionService`:
- `create(accountId, amount, currency, direction, description)`, `@Transactional`. It follows the
  ADR-0002 order:
  1. `accountMapper.findById` → `AccountNotFoundException`, mapped to 404 with the endpoint's code.
  2. `balanceMapper.exists(accountId, currency)` → false → new `CurrencyNotOpenException`, mapped to 422 `INVALID_CURRENCY`.
  3. `amount.setScale(2, UNNECESSARY)` (ADR-0001), `id = UUID.randomUUID()`,
     `delta = IN ? amount : amount.negate()`.
  4. `balanceMapper.applyDelta(accountId, currency, delta)` → empty → new
     `InsufficientFundsException`, mapped to 422 `INSUFFICIENT_FUNDS`. A present result is `balanceAfter`.
  5. `transactionMapper.insert(...)` with that `balance_after`.
  6. `outboxWriter.write(TRANSACTION_CREATED, ...)`, then `write(BALANCE_UPDATED, ...)`.
  7. Return the `Transaction` record.
- `list(accountId)`, `@Transactional(readOnly = true)`: account existence, then
  `transactionMapper.findByAccountId`.
- New domain types: record `Transaction(id, accountId, amount, currency, direction, description, balanceAfter)`
  and the two exceptions.
- `ApiExceptionHandler` gets one `@ExceptionHandler` per exception, both 422. Both go through
  `handleExceptionInternal`. Both exceptions extend `RuntimeException`, so the transaction rolls back.

### 4. Mappers (`persistence`), skill step 4
- `BalanceMapper.applyDelta`: the ADR-0002 SQL, as `@Select("UPDATE … RETURNING available_amount")`
  returning `Optional<BigDecimal>`, with `@Options(flushCache = TRUE)`. MyBatis treats it as a
  select, and its session cache would otherwise replay a result for identical params within one transaction.
- `BalanceMapper.exists(accountId, currency)`: `SELECT EXISTS(...)`.
- New `TransactionMapper`: `insert` (plain `@Insert`; `seq` is assigned by bigserial), and
  `findByAccountId … ORDER BY seq` returning `List<Transaction>`. The row maps straight onto the record.
- No migration: V1 already has `account_transaction`.

### 5. Outbox (`messaging`), skill step 5
- `EventTypes.TRANSACTION_CREATED = "transaction.created"` and `BALANCE_UPDATED = "balance.updated"`.
- `TransactionCreatedData(transactionId, accountId, amount, currency, direction, description, balanceAfter)`
  and `BalanceUpdatedData(accountId, currency, availableAmount, transactionId)`: exactly the §5 fields.
- Emission order is `transaction.created`, then `balance.updated`, both after `applyDelta`. §5 gets this added.

### 6. Tests via `test-writer`, skill step 6
Two classes, written black-box. The agent gets the spec and the case list, never `src/main`.

**`TransactionApiIT`**, POST:
- Happy path:
  - IN then OUT: 201, the response fields, `balanceAfter` scale 2, and the account's GET balance matches;
  - `10.500` is accepted as 10.50;
  - no `Location` header.
- 400 per field, with `errors[]`:
  - currency: missing, `null`, `JPY`, `eur` → `INVALID_CURRENCY`;
  - direction: missing, `in`, `SIDEWAYS` → `INVALID_DIRECTION`;
  - amount: missing, 0, −1, `10.555`, `1e18` → `INVALID_AMOUNT`;
  - description: missing, `""`, `"   "` → `DESCRIPTION_MISSING`; 256 characters, or a control
    character inside text (`"a\u0001b"`, a tab, a newline) → `VALIDATION_FAILED`; malformed JSON → `VALIDATION_FAILED`.
- Multi-field failure: top-level `code` follows priority (currency > direction > amount > description),
  and `errors[]` is sorted by code, then field.
- 422:
  - a supported currency the account doesn't hold → `INVALID_CURRENCY`;
  - an OUT over the balance → `INSUFFICIENT_FUNDS`, and the balance is unchanged;
  - an OUT of exactly the balance → 201 with `balanceAfter` 0.00.
- Path ID: malformed (`not-a-uuid`, `1-2-3-4-5`) → 400 `ACCOUNT_MISSING`; unknown → 404 `ACCOUNT_MISSING`.
- Order of checks (§3 rule 1):
  - unknown account + bad amount → 400 `INVALID_AMOUNT`;
  - unknown account + currency not held → 404;
  - currency not held + OUT over the balance → 422 `INVALID_CURRENCY`.
- Outbox:
  - after a 201: exactly 2 new rows, `transaction.created` then `balance.updated`, envelope and
    `data` per §5, and `data.transactionId` matches the response;
  - every rejected case (400, 404, both 422s) leaves no new row for the account.
- `@Disabled("task 6: reject string amounts, §2 / §3 rule 4")` tests (decision 2):
  - `"amount": "10.50"` → 400 `INVALID_AMOUNT`;
  - `"amount": "abc"` → 400 `INVALID_AMOUNT` (same rule; drop it if you only want the string case).

**`TransactionApiIT`**, GET:
- 200 `[]` for a new account;
- a list in insert order with `balanceAfter` as a running balance;
- only the account's own transactions;
- malformed ID → 400 `INVALID_ACCOUNT`; unknown ID → 404 `INVALID_ACCOUNT`.

**`BalanceConcurrencyIT`**, §6 criterion 3:
- **OUTs only:**
  - Open with IN 100.00, then fire 50 concurrent OUTs of 7.00 (a start latch, then a 50-thread executor).
  - Exactly 14 return 201; 36 return 422 `INSUFFICIENT_FUNDS`. The final balance is 2.00.
  - Every `balanceAfter` ≥ 0.
  - The last `balanceAfter` by `seq` (GET list) equals the stored balance (GET account).
- **Mixed:**
  - Open with IN 50.00, then fire 60 concurrent requests, a mix of OUT 10.00 and IN 5.00.
  - Every `balanceAfter` ≥ 0.
  - The opening balance plus the Σ of the listed transactions equals the final balance.
  - In `seq` order, each `balanceAfter` = previous ± amount, so `seq` order is commit order.
  - The last `balanceAfter` equals the stored balance.
- **Both:**
  - Every response is 201 or 422 `INSUFFICIENT_FUNDS`; nothing else, in particular no 5xx (decision 3).
  - Outbox rows = 2 × successful transactions, so the 422s left no phantom events.

Mismatches and ambiguities go to you before any fix (skill step 6).

### 7. `./gradlew check`, skill step 7
Coverage gate (lines ≥ 0.80, branches ≥ 0.70); fill any gap through `test-writer` with a spec-backed case.

### 8. Doc sync, skill step 8
- design.md §2: POST transaction is 201 with no `Location`.
- §3:
  - "Supported currency the account has no balance in" comes before funds (already rule 1 by implication; say it);
  - an interim note that string or unparseable amounts are `VALIDATION_FAILED` until task 6.
- §5: the transaction emission order.
- `docs/sdlc-plan.md` status: task 3 done.

### 9. Skill retrospective (your ask)
Log friction while working. After `check` is green, list what SKILL.md missed or got wrong, then
propose a revised SKILL.md as a diff for your review. Known so far:
- Location is unconditional.
- "Add it to the resource's existing controller" doesn't cover a new or nested resource.
- `codeFor` guidance doesn't handle a standard constraint (`@NotBlank`) that needs different codes per field.
- Nothing covers 422 business exceptions.
- Nothing covers MyBatis `UPDATE … RETURNING` and the session cache.
- Nothing covers concurrency tests.
- No pattern for `@Disabled` spec-gap tests.
- No rule that domain exceptions are unchecked (`RuntimeException`), which `@Transactional` rollback depends on.
- Declare `@PathVariable` before `@RequestBody`: Spring resolves in declaration order, which is what
  makes a malformed path ID win (§3 rule 1).
- A rule the test-writer needs must be in design.md: `10.500` lived only in ADR-0001, which the agent can't read.

## Decisions (confirmed)
1. POST transaction returns 201 **without** `Location`: there's no single-transaction GET to point at.
2. String amounts (and parse errors generally) are fixed in task 6. `test-writer` writes the tests now
   as `@Disabled("task 6: reject string amounts, §2 / §3 rule 4")` so the gap shows in reports.
3. Both concurrency tests assert **every** response is 201 or 422 `INSUFFICIENT_FUNDS`, never 5xx.
   Pool timeouts or lock errors would surface as 500s, and the sums would still reconcile.
4. `CurrencyNotOpenException` and `InsufficientFundsException` extend `RuntimeException`, because
   checked exceptions don't trigger `@Transactional` rollback. This rule goes on the skill-revision list.
5. The two 422 handlers go through `handleExceptionInternal`, like every other handler.
6. Descriptions are single-line: `@FreeText` keeps rejecting tabs and newlines on `description`.
7. **`10.500` is accepted as 10.50** (from the test-writer's mismatch; option A). `@Digits` counts a
   `BigDecimal`'s trailing zeros, so `@ValidAmount` gets its own validator instead.
   - It strips trailing zeros, then requires decimals = `max(0, scale)` ≤ 2 and integer digits =
     `precision − scale` ≤ 17.
   - The integer digits are checked first, as a `long` (stripping doesn't change them), so an extreme
     exponent can't overflow `int` or make `stripTrailingZeros` throw a 500.
   - ADR-0001's reasoning is corrected; design.md §2 says trailing zeros don't count.
   - Added tests: `10.5000000` → 201 (10.50); `1e2` → 201 (100.00); `0.000` and `0.001` → 400;
     `99999999999999999.99` → 201; `1e18` → 400.
8. **A malformed path ID wins over the body** (design.md §3 rule 1). `@PathVariable` is declared
   before `@RequestBody`, because Spring resolves arguments in declaration order.

## Files
- **New:**
  - `api/{TransactionController, CreateTransactionRequest, TransactionResponse, SupportedDirection(+Validator), ValidAmount, DescriptionPresent}`;
  - `domain/{Direction, Transaction, TransactionService, CurrencyNotOpenException, InsufficientFundsException}`;
  - `persistence/TransactionMapper`;
  - `messaging/{TransactionCreatedData, BalanceUpdatedData}`;
  - tests `TransactionApiIT`, `BalanceConcurrencyIT` (written by `test-writer`);
  - `docs/plans/task-3-transactions.md` (this plan, saved first).
- **Changed:** `ApiExceptionHandler` (`codeFor`, two 422 handlers), `BalanceMapper`, `EventTypes`,
  `docs/design.md`, `docs/sdlc-plan.md`, and `.claude/skills/add-endpoint/SKILL.md` (after your review).

## Verification
1. `./gradlew check` is green: all tests, including the concurrency test, plus the JaCoCo gate.
2. Manually: `docker compose up -d`, then `./gradlew bootRun`. `curl` an IN, an OUT, an
   insufficient-funds OUT, and the GET list. `psql` shows the outbox rows. Swagger lists both endpoints.
3. Show you the `test-writer` findings and wait before any commit (per memory). Then commit on
   `feat/transactions`.
