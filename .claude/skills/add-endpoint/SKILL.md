---
name: add-endpoint
description: Recipe for adding a REST endpoint to this banking service — DTO and validation mapped to ErrorCodes, controller with @NotFoundCode and springdoc, @Transactional service, MyBatis SQL, outbox writes, black-box tests via the test-writer agent, then ./gradlew check and a design.md sync. Use whenever a design.md §2 endpoint is built or changed.
---

# Add an endpoint

Extracted from building `POST /accounts`. Follow the steps in order. The spec is design.md §2 (API),
§3 (errors), §4 (data) and §5 (events); `CLAUDE.md` has the hard rules. When the spec and what you
are about to build disagree, stop and ask; don't silently pick one.

Reference implementation: `AccountController.create`, `CreateAccountRequest`, `AccountService.create`,
`AccountMapper` / `BalanceMapper`, `OutboxWriter`, `ApiExceptionHandler`.

## 1. DTO and validation (`api`)
- **Request:** a `record` named `<Verb><Thing>Request`. Put Bean Validation on the components.
  Bind `currency` and `direction` as `String`, never as enums, so a bad value fails validation
  instead of Jackson parsing (§2). Money is `BigDecimal` (ADR-0001).
- **Map every constraint to its §3 code.** `ApiExceptionHandler.codeFor` decides the code from the
  constraint: `@SupportedCurrency` gives `INVALID_CURRENCY`; everything else gives
  `VALIDATION_FAILED`. For each field, check its §3 row:
  - If the row's code is `VALIDATION_FAILED`, standard constraints are enough.
  - Otherwise, use a constraint that maps to that code, and extend `codeFor` if it doesn't yet.
    "Missing" counts: if §3 says a missing field gets the field's code (e.g. currency), the
    field's constraint must reject null itself, as `@SupportedCurrency` does, rather than a
    separate `@NotNull` that would give `VALIDATION_FAILED`.
  - New codes go in `ErrorCode`, in the §3 rule 3 priority order (the enum order *is* the priority).
- **Response:** a `record` named `<Thing>Response` with a static `from(domainObject)`. Emit enums
  as their `name()`. Amounts are `BigDecimal` scale 2 straight from the DB, never rescaled.
- **Validate the body only:** use `@Valid @RequestBody`. Put no constraint annotations on
  controller parameters (path variables, query params): that switches on Spring method validation,
  which `ApiExceptionHandler` doesn't map.

## 2. Controller method (`api`)
- Add it to the resource's existing `*Controller` (one per resource, `@RequestMapping` on the class).
- **Path IDs are typed `UUID`.** Any method with an `{accountId}` path variable gets
  `@NotFoundCode(ErrorCode.…)` with that endpoint's §3 code (`ACCOUNT_NOT_FOUND`, `ACCOUNT_MISSING`
  or `INVALID_ACCOUNT`). It drives both the 400 for a malformed ID and the 404 for an unknown one.
  If it's missing, a 404 turns into a 500.
- **Status:** 201 for a create, with `ResponseEntity.created(URI.create("/…/" + id))`; 200 otherwise.
- **springdoc:** `@Operation(summary = …)`, and one `@ApiResponse` per status the endpoint can return.
  Error responses use `content = @Content(mediaType = "application/problem+json", schema =
  @Schema(implementation = ProblemDetail.class))`. Name the codes in the description.

## 3. Service method (`domain`)
- Add a public method to the resource's `*Service`. Annotate writes `@Transactional` and reads
  `@Transactional(readOnly = true)`.
- It takes and returns domain types (`Account`, `Currency`, …), not DTOs. The controller converts
  validated strings with `Currency.valueOf`.
- An unknown account throws `AccountNotFoundException(id)`. Do the existence check *before*
  business rules, and after validation, which has already run (§3 rule 1).
- Generate new IDs with `UUID.randomUUID()` here, before any insert (the outbox payloads need them).
- **Balances:** change only through `BalanceMapper.applyDelta` (ADR-0002). 0 rows means 422
  `INSUFFICIENT_FUNDS`. Never write another `UPDATE balance`.

## 4. Mapper SQL (`persistence`)
- Use annotation `@Mapper` interfaces with plain SQL in `@Select` / `@Insert` / `@Update`. Don't use
  XML.
- Parameters bind by name (the build compiles with `-parameters`), so `@Param` isn't needed.
- Results map into records by constructor argument name, and `snake_case` columns match
  `camelCase` arguments. A flat row that differs from the domain type gets its own `*Row` record.
  `UUID` and enums map automatically (`UuidTypeHandler`, MyBatis's enum-by-name handler).
- Single-row lookups return `Optional<…>`. Lists need an explicit `ORDER BY`: transactions by
  `seq`, never `created_at`; balances by `currency`.
- Schema changes go in a new `V<n>__*.sql`. Never edit an applied migration.

## 5. Outbox writes (`messaging`), only if the endpoint writes
- Write one event per inserted or updated row (ADR-0004), with the full state after the change.
  Read that state back from the DB (e.g. the `RETURNING` row) instead of reconstructing it.
- Call `outboxWriter.write(EventTypes.X, accountId, new XData(…))` inside the service's transaction,
  *after* the balance update (ADR-0003). Never publish to RabbitMQ from a service.
- New event types: a constant in `EventTypes`, plus a `*Data` record whose components are exactly
  the §5 `data` fields. If §5 doesn't list the event yet, add it to §5 first.
- Write events in a deterministic order, and make §5 state it (e.g. `account.created`, then
  `balance.created` in currency order).

## 6. Tests: have the `test-writer` agent write them
- Launch the `test-writer` agent. Don't write the tests yourself, and don't give it production code.
- Give it:
  - the endpoint;
  - the case list: the happy path, every §3 row that applies to the endpoint, the §3 rule 1 order
    of checks (validation before existence before business rules), a multi-field failure (rule 3)
    and, for writes, the §5 outbox rows plus "a rejected request leaves no outbox row";
  - for a path ID: malformed → 400, unknown → 404, both with the endpoint's code;
  - any decision recorded in the task plan that the spec doesn't yet state.
- It runs the tests and reports passes, failures, mismatches and ambiguities.
- **Show mismatches and ambiguities to the user before changing anything.** For each one, the
  user decides whether the code or the spec is wrong. Then fix the code or update design.md. A
  test changes only if the spec changes, and that change is made by hand, not by the agent.

## 7. `./gradlew check`
Compile, all tests, and the JaCoCo gate (lines ≥ 0.80, branches ≥ 0.70). If coverage misses, find
the uncovered branch in `build/reports/jacoco/test/html/index.html`. Then add a spec-backed case
via `test-writer`; if no spec case reaches it, ask the user — it's either dead code or a spec gap.
Never lower the thresholds.

## 8. Sync design.md with what was built
- **§2:** paths, status codes and the response shape match.
- **§3:** every code the endpoint returns is in the table, and every clarification you made is
  written down.
- **§5:** events, `data` fields and emission order match.
- Also check that the task plan's decisions are reflected in the spec, so the next test-writer
  run doesn't need them passed in.

## Contract points already settled (don't relitigate)
- `type` is omitted from ProblemDetails, since RFC 9457 treats a missing `type` as `about:blank`.
- `title`, `detail` and `errors[].message` are human-readable, not contract.
- `errors[]` is sorted by code priority, then by field. A list element's field is indexed (`currencies[1]`).
- Path IDs: canonical UUID only (strict binder, `StrictUuidBinding`). Malformed → 400 with the
  endpoint's `@NotFoundCode`; never parse with lenient `UUID.fromString` alone.
- Free-text fields (`customerId`, `description`, any future one) carry `@FreeText`: no control
  characters, so NUL can't reach Postgres as a 500. Violations are `VALIDATION_FAILED`.
- Protocol errors (404 route, 405, 415, other 4xx, 500) get status-based codes from
  `ApiExceptionHandler.createResponseEntity`; a 500's `detail` is generic. Don't add per-endpoint handling.
- `Location` is relative (`/accounts/{id}`).
- Balances are listed in currency order (EUR, GBP, SEK, USD).
