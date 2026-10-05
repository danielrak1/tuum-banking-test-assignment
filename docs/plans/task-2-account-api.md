# Task 2: Create / get account (`feat/account-api`)

> **Status:** done 2026-10-05. Built, tested (`./gradlew check` green: lines 0.98, branches 0.88),
> reviewed (`/code-review` + silent-failure-hunter; findings A–E, K fixed; F, H deferred to tasks 6, 5).

## Context
This is Stage 3, task 2 of 7 in `docs/sdlc-plan.md`. The skeleton (PR #1) is merged: schema V1, compose,
the shared `TestcontainersConfiguration`, and the JaCoCo gate (lines ≥ 0.80, branches ≥ 0.70).
This task adds the first two endpoints from design.md §2: `POST /accounts` and `GET /accounts/{accountId}`.

Scope, as you chose:
- **Errors:** the account endpoints' contract errors land now. Task 6 adds the transaction codes.
- **Events:** create account writes its outbox rows now. Task 5 adds the poller and RabbitMQ topology,
  so rows pile up unpublished until then.
- **Tooling:** `test-writer` is created now and writes the tests. `add-endpoint` is written after
  `POST /accounts` is built by hand, then used for the GET endpoint.

## Approach (all under `com.danielrak.banking`)

### 1. Test infrastructure (first, because every test depends on it)
- `build.gradle`: add `testImplementation 'org.springframework.boot:spring-boot-starter-webmvc-test'`.
  In Boot 4 it is the starter that brings `RestTestClient`.
- New `IntegrationTest` meta-annotation: `@SpringBootTest(webEnvironment = RANDOM_PORT)`,
  `@AutoConfigureRestTestClient` and `@Import(TestcontainersConfiguration.class)`.
  **Why:** Spring's context cache only reuses a context, and so its containers, when the test
  configuration is identical. The smoke test (MOCK) and an HTTP test (RANDOM_PORT) would each start
  their own containers. Every integration test uses this one annotation instead; `BankingApplicationTests` switches to it.
- `CLAUDE.md` "Tests" rule: replace the `@Import(...)` wording with "annotate integration tests with
  `@IntegrationTest` and nothing that changes the context".
- **Isolation:** tests share one database, so each test creates its own account with a fresh UUID
  and only checks its own rows.

### 2. Domain + persistence
- `domain`:
  - `Currency` enum (EUR, SEK, GBP, USD);
  - records `Account(id, customerId, country, List<Balance> balances)` and `Balance(currency, availableAmount)`;
  - `AccountNotFoundException(UUID)`.
- `persistence`: annotation-based `@Mapper`s, with plain SQL and no XML:
  - `AccountMapper.insert`, and `AccountMapper.findById` returning `Optional<AccountRow>`. `AccountRow`
    is a flat record (`id, customerId, country`); `AccountService` builds the `Account` from it plus
    `BalanceMapper.findByAccountId`;
  - `BalanceMapper.insert(accountId, currency)`, so the amount takes the DB default of 0;
  - `BalanceMapper.findByAccountId`, `ORDER BY currency` so the order is deterministic;
  - `OutboxMapper.insert(eventId, routingKey, payload)` with `CAST(#{payload} AS jsonb)`.
- `application.yml`: `mybatis.configuration.arg-name-based-constructor-auto-mapping: true`, so
  MyBatis maps result rows into records by constructor argument name. The Boot Gradle plugin already compiles with `-parameters`.
- No `applyDelta` yet; it comes with transactions in task 3.

### 3. Messaging: OutboxWriter (rows only)
- `messaging.OutboxWriter.write(String eventType, UUID accountId, Object data)`.
  - It builds the envelope `{eventId, eventType, occurredAt, accountId, data}` (design.md §5).
  - It serialises with Boot's auto-configured Jackson 3 `JsonMapper`, then inserts through `OutboxMapper`.
  - It uses `@Transactional(propagation = MANDATORY)`, so calling it outside a business transaction fails loudly (ADR-0003).
- Data records `AccountCreatedData(accountId, customerId, country)` and `BalanceCreatedData(accountId, currency, availableAmount)`.
- Event type constants: `account.created`, `balance.created`.

### 4. Error contract for these endpoints (`api`)
- **`ErrorCode` enum:** declared in priority order (currency > direction > amount > description > other),
  so the top-level `code` is the highest-priority code among `errors[]` (design.md §3 rule 3). Only the
  account codes are used now, but the whole enum is declared.
- **`@NotFoundCode(ErrorCode)`** on each controller method: the per-endpoint not-found code (§3 rule 5).
  The handler reads it from the `HandlerMethod` (404 for an unknown ID) or from the type-mismatch
  exception's `MethodParameter` (400 for a malformed ID). This lets path IDs stay typed as `UUID`, and
  task 3 reuses it for `ACCOUNT_MISSING` and `INVALID_ACCOUNT`.
- **`ApiExceptionHandler extends ResponseEntityExceptionHandler`:**
  - `handleMethodArgumentNotValid`: 400, with `errors[]` of `{field, code, message}`. A field error's
    code comes from its constraint: `@SupportedCurrency` gives `INVALID_CURRENCY`; everything else
    gives `VALIDATION_FAILED`.
  - `handleHttpMessageNotReadable`: 400 `VALIDATION_FAILED`. Mapping Jackson field paths to specific
    codes (`amount`) is task 6.
  - `handleTypeMismatch`: 400, with the `@NotFoundCode` code.
  - `AccountNotFoundException`: 404, with the `@NotFoundCode` code.
- **`@SupportedCurrency`:** a custom constraint on `String`, used as `List<@SupportedCurrency String>`.
  Task 3 reuses it on the transaction `currency` field.

### 5. `POST /accounts` (by hand: this becomes the recipe)
- **`CreateAccountRequest`:**
  - `customerId`: `@NotBlank @Size(max = 64)`;
  - `country`: `@NotNull @Pattern("[A-Z]{2}")`;
  - `currencies`: `@NotEmpty @UniqueElements List<@NotNull @SupportedCurrency String>`.
- **`AccountService.create`** (`@Transactional`):
  1. Generate the UUID.
  2. Insert the account.
  3. Insert one balance per currency.
  4. Write the outbox rows: `account.created`, then `balance.created` × N (ADR-0004 action item 2).
  5. Return the `Account`.
- **`AccountController`:** returns 201 with `Location: /accounts/{id}` and an `AccountResponse`
  (`accountId, customerId, country, balances[{currency, availableAmount}]`). Amounts are `BigDecimal` scale 2.
- **springdoc:** `@Operation` / `@ApiResponse` annotations with a short summary per status.

### 6. `test-writer` agent, then tests for POST
- **`.claude/agents/test-writer.md`:** writes Testcontainers integration tests **black-box**.
  - It works from `intent.md` and design.md §2 (API), §3 (errors) and §5 (events) only, not from the
    production code.
  - When a test fails because the code and the spec disagree, it **reports the mismatch as a finding**
    (spec reference, expected, actual) and leaves the test as written. You or I then decide whether the
    code or the spec is wrong.
  - One exception until task 5's poller exists: the §5 checks read `outbox_event` rows directly,
    because no message reaches RabbitMQ yet.
  - Rules it follows:
    - `@IntegrationTest` + `RestTestClient`;
    - assert the ProblemDetail `code` and `errors[]`, not just the status;
    - money as `BigDecimal` compared with `compareTo`, never `double`;
    - fresh UUIDs per test;
    - check outbox rows by `payload->>'accountId'`.
  - It runs `./gradlew test --tests …` and reports which tests pass, which fail, and the mismatch findings.
- **⏸ Pause 1:** stop after writing `test-writer.md`, for your review, before it is used.
- **`AccountApiIT` cases (POST):**
  - 201 with Location and zero balances in every currency;
  - 400 `INVALID_CURRENCY` for `"JPY"`, lowercase `"eur"` and a null element;
  - 400 `VALIDATION_FAILED` for empty, duplicate or missing currencies, a bad country, a blank or 65-character
    customerId, and malformed JSON;
  - several failing fields: `code` = `INVALID_CURRENCY`, and `errors[]` lists all of them;
  - outbox: exactly 1 + N rows in `id` order (`account.created` first), with a payload envelope that matches §5;
  - a rejected request leaves no outbox row.

### 7. `add-endpoint` skill, then `GET /accounts/{accountId}` through it
- **`.claude/skills/add-endpoint/SKILL.md`**, the recipe extracted from steps 5–6:
  1. Write the DTO and validation, mapping each constraint to an `ErrorCode`.
  2. Add the controller method with `@NotFoundCode` and springdoc annotations.
  3. Add the `@Transactional` service method.
  4. Add mapper SQL.
  5. Add outbox writes, if the endpoint writes anything.
  6. Have `test-writer` write the tests from the design.md §3 rows for the endpoint.
  7. Run `./gradlew check`.
  8. Check that design.md matches what was built.
- **⏸ Pause 2:** stop after writing `SKILL.md`, for your review, before it is used for GET.
- **GET, built by following the skill:**
  - `AccountService.get` throws `AccountNotFoundException`;
  - 200 with the balances;
  - 404 `ACCOUNT_NOT_FOUND` for an unknown ID, 400 `ACCOUNT_NOT_FOUND` for a malformed one.

## Decisions (confirmed)
1. **Missing vs empty `currencies`:** a null, empty or duplicate list gives `VALIDATION_FAILED`; a null
   or unsupported *element* gives `INVALID_CURRENCY`. design.md §3 lists "currency missing" under
   `INVALID_CURRENCY` but "empty currencies" under `VALIDATION_FAILED`. I read "missing" as applying to
   the transaction's single `currency` field, and will add that clarification to §3 in this task.
2. **Balance order:** in the response, balances are ordered alphabetically by currency (EUR, GBP, SEK, USD), not in request order.

## Files
- **New:**
  - `api/{AccountController, CreateAccountRequest, AccountResponse, ApiExceptionHandler, ErrorCode, NotFoundCode, SupportedCurrency(+Validator)}`;
  - `domain/{Currency, Account, Balance, AccountService, AccountNotFoundException}`;
  - `persistence/{AccountMapper, AccountRow, BalanceMapper, OutboxMapper}`;
  - `messaging/{OutboxWriter, EventEnvelope, EventTypes, AccountCreatedData, BalanceCreatedData}`;
  - tests: `IntegrationTest`, `AccountApiIT`;
  - `.claude/agents/test-writer.md`, `.claude/skills/add-endpoint/SKILL.md`.
- **Changed:** `build.gradle`, `application.yml`, `BankingApplicationTests`, `CLAUDE.md` (Tests rule),
  design.md §3 (decision 1).

## Verification
1. **Automated:** `./gradlew check` passes: every case above, and both coverage thresholds met on real code.
2. **Manual:** `docker compose up -d`, then `./gradlew bootRun`.
   - `curl` a create, a get, a 400 and a 404, and compare them with design.md §2 and §3.
   - `psql` shows the outbox rows with the §5 envelope.
   - Swagger UI at `/swagger-ui.html` lists both endpoints.
3. **Review before the PR:** have `/code-review` check the diff against the ADR rules, then commit on
   `feat/account-api` and open PR #2.
