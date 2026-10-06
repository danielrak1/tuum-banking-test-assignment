---
name: add-endpoint
description: Recipe for adding a REST endpoint to this banking service — task plan, DTO and validation mapped to ErrorCodes, controller with @NotFoundCode and springdoc, @Transactional service with 422 business rules, MyBatis SQL, outbox writes, black-box tests via the test-writer agent (with a concurrency test for balance changes), then ./gradlew check and a design.md sync. Use whenever a design.md §2 endpoint is built or changed.
---

# Add an endpoint

Extracted from building `POST /accounts`, revised after the transaction endpoints (task 3). Follow
the steps in order. The spec is design.md §2 (API), §3 (errors), §4 (data) and §5 (events);
`CLAUDE.md` has the hard rules. When the spec and what you are about to build disagree, stop and
ask; don't silently pick one.

Reference implementations:
- **Create, top-level resource:** `AccountController.create`, `CreateAccountRequest`, `AccountService.create`.
- **Create, nested resource with business rules:** `TransactionController.create`,
  `CreateTransactionRequest`, `TransactionService.create`, `BalanceMapper.applyDelta`.
- **Shared:** `OutboxWriter`, `ApiExceptionHandler`, `ValidAmount`.

## 0. Task plan
- Plan mode first. Save the agreed plan to `docs/plans/task-<n>-<name>.md` before writing code.
- Its **Decisions** section holds everything you settled that the spec doesn't state yet. Step 6
  passes them to `test-writer`; step 8 writes them into design.md.

## 1. DTO and validation (`api`)
- **Request:** a `record` named `<Verb><Thing>Request`. Put Bean Validation on the components.
  Bind `currency` and `direction` as `String`, never as enums, so a bad value fails validation
  instead of Jackson parsing (§2).
- **Money** is `BigDecimal` with `@ValidAmount` (ADR-0001). Never use `@Digits` on money: it counts
  a `BigDecimal`'s trailing zeros, so it rejects `10.500`.
- **Map every constraint to its §3 code.** The mapping lives in `ApiExceptionHandler.codeFor`; add a
  case there for a new constraint. For each field, check its §3 row:
  - If the row's code is `VALIDATION_FAILED`, standard constraints are enough.
  - Otherwise, every way the field can fail with that code must come from one constraint that
    `codeFor` maps. Reuse an existing one, or add one:
    - compose standard constraints under a new name with `@ReportAsSingleViolation` (as
      `@FreeText` wraps `@Pattern`). Mapping a standard constraint itself would change its code on
      every field that uses it (`customerId` is `@NotBlank` too);
    - or write a validator when the rule needs logic (`ValidAmountValidator`, `DescriptionPresentValidator`).
  - "Missing" counts: if §3 gives a missing field the field's code, that constraint must reject
    null itself, as `@SupportedCurrency` does, rather than a separate `@NotNull` that would give
    `VALIDATION_FAILED`.
  - New codes go in `ErrorCode`, in the §3 rule 3 priority order (the enum order *is* the priority).
- **Response:** a `record` named `<Thing>Response` with a static `from(domainObject)`. Emit enums
  as their `name()`. Amounts are `BigDecimal` scale 2, never rescaled in the response.
- **Validate the body only:** use `@Valid @RequestBody`. Put no constraint annotations on
  controller parameters (path variables, query params): that switches on Spring method validation,
  which `ApiExceptionHandler` doesn't map.

## 2. Controller method (`api`)
- One `*Controller` per resource, with `@RequestMapping` on the class. A new resource gets its own
  controller, with its full path on the class, nested if it belongs to an account
  (`@RequestMapping("/accounts/{accountId}/transactions")`).
- **Path IDs are typed `UUID`.** Any method with an `{accountId}` path variable gets
  `@NotFoundCode(ErrorCode.…)` with that endpoint's §3 code (`ACCOUNT_NOT_FOUND`, `ACCOUNT_MISSING`
  or `INVALID_ACCOUNT`). It drives both the 400 for a malformed ID and the 404 for an unknown one.
  If it's missing, a 404 turns into a 500, and `NotFoundCodeConventionTest` fails the build.
- **Declare `consumes`/`produces = MediaType.APPLICATION_JSON_VALUE`** on every mapping (`produces`
  only for a GET). Spring then rejects a wrong `Content-Type` (415) or `Accept` (406) before the
  handler runs. Without it, the 406 comes only when the response is written, after the write has
  committed.
- **Declare `@PathVariable` before `@RequestBody`.** Spring resolves arguments in declaration
  order, which is what makes a malformed path ID win over a bad body (§3 rule 1).
  `NotFoundCodeConventionTest` enforces the order.
- **Status:** 200 for a read. A create returns 201:
  - with `ResponseEntity.created(URI.create("/…/" + id))` only if a GET for the new resource exists;
  - otherwise with `ResponseEntity.status(HttpStatus.CREATED)`, and §2 says "no `Location`".
- **springdoc:** `@Operation(summary = …)`, and one `@ApiResponse` per status the endpoint can return.
  Error responses use `content = @Content(mediaType = "application/problem+json", schema =
  @Schema(implementation = ProblemDetail.class))`. Name the codes in the description.

## 3. Service method (`domain`)
- Add a public method to the resource's `*Service`, or create the service for a new resource.
  Annotate writes `@Transactional` and reads `@Transactional(readOnly = true)`.
- It takes and returns domain types (`Account`, `Transaction`, `Currency`, …), not DTOs. The
  controller converts validated strings with `Currency.valueOf` / `Direction.valueOf`.
- **Order of checks** (§3 rule 1). Validation has already run when the service is called. Then:
  1. account existence: an unknown account throws `AccountNotFoundException(id)`;
  2. the business rules, in the order §3 rule 1 lists them.
- **A failed business rule** throws an unchecked domain exception (`extends RuntimeException`):
  `@Transactional` doesn't roll back on a checked one. Map it in `ApiExceptionHandler` with an
  `@ExceptionHandler` that returns `unprocessable(ex, ErrorCode.X, request)`. That gives a 422
  through `handleExceptionInternal`, like every other handler.
- Normalise amounts with `setScale(2, RoundingMode.UNNECESSARY)` before using them. Validation
  guarantees nothing is rounded, and `UNNECESSARY` asserts it (ADR-0001).
- Generate new IDs with `UUID.randomUUID()` here, before any insert (the outbox payloads need them).
- **Balances:** change only through `BalanceMapper.applyDelta` (ADR-0002). An empty result means 422
  `INSUFFICIENT_FUNDS`. Never write another `UPDATE balance`.

## 4. Mapper SQL (`persistence`)
- Use annotation `@Mapper` interfaces with plain SQL in `@Select` / `@Insert` / `@Update`. Don't use
  XML.
- Parameters bind by name (the build compiles with `-parameters`), so `@Param` isn't needed. A
  single record parameter binds its components: `insert(Transaction t)` uses `#{accountId}`.
- Results map into records by constructor argument name, and `snake_case` columns match
  `camelCase` arguments. A flat row that differs from the domain type gets its own `*Row` record.
  `UUID` and enums map automatically (`UuidTypeHandler`, MyBatis's enum-by-name handler).
- **A write that returns a value** (`UPDATE … RETURNING`) is a `@Select` (see `applyDelta`). It is
  safe only because the local cache is statement-scoped (`mybatis.configuration.local-cache-scope:
  statement`). The default, session scope, would replay an earlier result for the same arguments
  within one transaction instead of running the write.
- Single-row lookups return `Optional<…>`. Existence checks are `SELECT EXISTS (…)` returning
  `boolean`. Lists need an explicit `ORDER BY`: transactions by `seq`, never `created_at`;
  balances by `currency`.
- Schema changes go in a new `V<n>__*.sql`. Never edit an applied migration.

## 5. Outbox writes (`messaging`), only if the endpoint writes
- Write one event per inserted or updated row (ADR-0004), with the full state after the change.
  - Values the database computes (defaults, `RETURNING`) come from the database.
  - Values you inserted as-is can come from the record you inserted.
- Call `outboxWriter.write(EventTypes.X, accountId, new XData(…))` inside the service's transaction,
  *after* the balance update (ADR-0003). Never publish to RabbitMQ from a service.
- New event types: a constant in `EventTypes`, plus a `*Data` record whose components are exactly
  the §5 `data` fields. If §5 doesn't list the event yet, add it to §5 first.
- Write events in a deterministic order, and add that order to the per-request list in §5.

## 6. Tests: have the `test-writer` agent write them
- Launch the `test-writer` agent. Don't write the tests yourself, and don't give it production code.
- It reads only intent.md, design.md and CLAUDE.md, not the ADRs. A rule that lives only in an ADR
  or in the task plan must be in the brief; otherwise the agent tests the literal reading of design.md.
- Give it:
  - the endpoint;
  - the case list:
    - the happy path;
    - every §3 row that applies to the endpoint;
    - the §3 rule 1 order of checks: validation, then existence, then each business rule in order;
    - a multi-field failure (rule 3);
    - for writes, the §5 events on the queue (through `BankingEvents.awaitEvents`), plus "a
      rejected request publishes nothing" (after `fence()`) for each rejection status;
  - for a path ID:
    - malformed → 400 and unknown → 404, both with the endpoint's code;
    - malformed plus a bad body → 400 with the path code;
  - for an amount: the boundaries (17 integer digits, 2 decimals), trailing zeros (`10.500`), zero,
    and exponents (`1e2`, `1e18`, an extreme one such as `100e2147483647`);
  - for a balance change, a concurrency test (design.md §6):
    - concurrent requests released by a start latch;
    - every response has an expected status, never a 5xx;
    - the exact success count;
    - the sums reconcile, and the list in `seq` order is a running balance;
    - the 422s publish no events (after `fence()`), and the published events match the 201s;
  - for a rule the spec defers to a later task: write the test now, annotated
    `@Disabled("task <n>: …")`, so the gap shows in every test report;
  - the task plan's Decisions (step 0).
- It runs the tests and reports passes, failures, mismatches and ambiguities.
- **Show mismatches and ambiguities to the user before changing anything.**
  - For each one, the user decides whether the code or the spec is wrong.
  - Then fix the code or update design.md, plus the ADR if its reasoning was wrong.
  - A test changes only if the spec changes, and that change is made by hand, not by the agent.
  - For new cases from an updated spec, resume the same agent with `SendMessage`; it already knows
    its test classes.

## 7. `./gradlew check`
Compile, all tests, and the JaCoCo gate (lines ≥ 0.80, branches ≥ 0.70). If coverage misses, find
the uncovered branch in `build/reports/jacoco/test/html/index.html`. Then add a spec-backed case
via `test-writer`; if no spec case reaches it, ask the user — it's either dead code or a spec gap.
Never lower the thresholds.

## 8. Sync design.md with what was built
- **§2:** paths, status codes (with or without `Location`), the response shape, and the input rules
  as enforced (e.g. trailing zeros don't count).
- **§3:** every code the endpoint returns is in the table, every clarification you made is
  written down, and rule 1 lists the business rules in order.
- **§5:** events, `data` fields and emission order match.
- The task plan's Decisions are in the spec, so the next test-writer run doesn't need them passed
  in. Where CLAUDE.md or an ADR states the same rule, keep them consistent.

## Contract points already settled (don't relitigate)
The contract is in design.md, and it is settled:
- §2: status codes and `Location`, list order, and the input rules (e.g. trailing zeros, single-line free text).
- §3: the ProblemDetail shape, `errors[]` order, the order of checks, and protocol errors.
- §5: the events and their order.

Two implementation pitfalls that design.md doesn't state:
- **Path IDs:** bind them through the strict binder (`StrictUuidBinding`). Never parse them with
  `UUID.fromString` alone: it is lenient and accepts short groups such as `1-2-3-4-5`.
- **Free text:** every free-text field (`customerId`, `description`, any future one) carries
  `@FreeText`. Without it, a control character such as NUL reaches Postgres and fails as a 500.
