# Task 5: Errors (`feat/errors`)

> **Status:** done 2026-10-06 (PR #5). Built as planned; outcome at the end.

## Context
This is Stage 3, task 5 of 7 in `docs/sdlc-plan.md`, item 6 ("errors") in the build order. Today every
Jackson parse error returns `VALIDATION_FAILED`, which breaks design.md §3 rule 4. Jackson is also too
lenient: it coerces `"amount": "10.50"`, lets the last duplicate key win (`"amount": 1.00, …,
"amount": 5000.00` posts 5000.00), and stores `"description": 42` as `"42"`. Five `@Disabled("task 6: …")`
tests wait for these fixes (4 in `TransactionApiIT`, 1 in `AccountApiIT`).

**Scope:** map parse errors by field path, reject string amounts, duplicate keys and scalar coercion,
and enable those five tests, plus the extra tests and docs this needs. Out of scope: the `NUMERIC(19,2)`
overflow 422 (design.md §8). It stays open in sdlc-plan item 6.

## Decisions (confirmed in chat)
1. **A duplicate key returns `VALIDATION_FAILED` with no `errors[]`.** It makes the document
   malformed (rule 4's "anything else"), so it doesn't get the field's code. Jackson 3.1.5 throws a
   plain `StreamReadException` for it, with no field path and no subclass. That's the same type it
   throws for syntax errors. Telling the two apart would mean matching Jackson's message text.
   I hand-edit `rejectsDuplicateAmountKeyWithInvalidAmount` to expect `VALIDATION_FAILED` with no
   `errors[]` and rename it `…WithValidationFailed`. Its "nothing posted" assertion stays. This is a
   spec change, made by hand rather than by the agent.
2. **test-writer adds a small set of rule 4 cases** for the paths the five tests don't cover (step 4).
3. **Only databind errors are mapped by path.** That means `DatabindException` with a non-empty path
   (wrong JSON type, failed coercion, unparseable value). Stream-level errors (`StreamReadException`)
   stay `VALIDATION_FAILED` even when Jackson attached a path: syntax errors, duplicate keys, and
   stream constraints such as a number over 1000 digits. One example is `"currencies": ["EUR" "USD"]`,
   which is a syntax error inside the list.
4. **A field's code is the code of its own validation constraint.** The handler reads the target
   field's constraints through Bean Validation metadata and maps them with the existing `FIELD_CODES`.
   When there are several, it takes the highest-priority one, by `ErrorCode` order. So `amount` →
   `INVALID_AMOUNT`, `description` → `DESCRIPTION_MISSING` (not the `VALIDATION_FAILED` of
   `@Size`/`@FreeText`), `currencies[1]` (element constraint) → `INVALID_CURRENCY`, and
   `currencies`, `customerId` or `country` → `VALIDATION_FAILED`. With this there is one source of
   truth and no second path→code table to drift.
5. **A parse error gives a one-entry `errors[]`.** Jackson stops at the first failure, so other
   fields aren't validated. For example, `"amount": "abc"` plus a bad currency returns only
   `INVALID_AMOUNT`. design.md §3 states this.

## Approach (all under `com.danielrak.banking.api`)

### 1. Strict JSON input: new `JsonInputConfiguration` (`@Configuration`)
One `JsonMapperBuilderCustomizer` bean (Boot 4, `org.springframework.boot.jackson.autoconfigure`)
does three things:
- `enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)`, which rejects duplicate keys.
- `disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)`, which makes String → `BigDecimal` (and
  `""` → null) fail. Integer → `BigDecimal` is still allowed, as Jackson's `CoercionConfigs` keeps
  it, so `"amount": 10` stays valid.
- `withCoercionConfig(LogicalType.Textual, …)` with `Integer`, `Float` and `Boolean` set to
  `CoercionAction.Fail`, which makes `42`/`true` → `String` fail. `StringDeserializer` reports it as a
  databind error with a path.

It only touches Boot's HTTP mapper. Events use their own `EventJson` (task 4), so §5 is unaffected.

### 2. `ApiExceptionHandler`
- Constructor-inject `jakarta.validation.Validator`, which Boot provides.
- `handleHttpMessageNotReadable`: if `ex.getCause()` is a `DatabindException` with a non-empty
  `getPath()`, it builds the following. Otherwise it keeps today's response.
  - `field`: property names joined by `.`, with indices as `[i]`, e.g. `currencies[1]`.
  - `code`: `fieldCode(beanType, property, isElement)` (decision 4). `beanType` is the first path
    reference's `getFrom()`, which is a `Class` for record creators and otherwise the instance's class.
    A path deeper than `property[index]` gets `VALIDATION_FAILED`.
  - The body is `problem(BAD_REQUEST, "Malformed request body", code)` with a one-entry `errors[]`.
    It reuses `FieldProblem`, and the message is generic. Never Jackson's message, which leaks Java
    type names.
- `ApiExceptionHandlerTest` builds the handler with
  `Validation.buildDefaultValidatorFactory().getValidator()`.

### 3. Enable the waiting tests
Remove `@Disabled` from the five tests and the now-unused imports. Hand-edit the duplicate-key test
(decision 1).

### 4. test-writer (new black-box cases, in its existing classes)
The brief has rule 4 as enforced, plus decisions 1, 3, 4 and 5. Each case checks the status, the
`code`, the one-entry `errors[]` (`field` + `code`), and that nothing was posted or published:
- `"currency": 1` → `INVALID_CURRENCY`; `"direction": true` → `INVALID_DIRECTION`.
- `"amount": true` and `"amount": {}` → `INVALID_AMOUNT`; `"amount": ""` → `INVALID_AMOUNT`.
- Create account: `"currencies": ["EUR", 1]` → `INVALID_CURRENCY` at `currencies[1]`;
  `"currencies": "EUR"` → `VALIDATION_FAILED` at `currencies`.
- A body that isn't an object (`[]`) → `VALIDATION_FAILED`, no `errors[]`.
- A duplicate non-amount key (`description`) → `VALIDATION_FAILED`, no `errors[]`.
- Still valid: `"amount": 10` → 201 with `10.00`.

Mismatches and ambiguities are shown to you before anything changes.

### 5. Docs
- **design.md §2:** the `amount` row says a JSON string (`"10.50"`) is rejected. The free-text,
  `currency` and `direction` rows say they must be JSON strings (no number/boolean coercion).
- **design.md §3:**
  - Rule 4 is rewritten as built (decisions 3–5) and "Not built yet" is removed.
  - The malformed-JSON row adds "duplicate key".
  - The amount row's "unparseable" adds "or a JSON string".
- **sdlc-plan:** the status row shows tasks 1–5, item 6 is ✅ with a pointer to this plan, and the
  overflow bullet stays open.
- **This plan:** an outcome section at the end.

## Files
- New: `api/JsonInputConfiguration.java`
- Changed: `api/ApiExceptionHandler.java`, `ApiExceptionHandlerTest`, `TransactionApiIT`,
  `AccountApiIT` (plus test-writer's additions), `docs/design.md`, `docs/sdlc-plan.md`,
  `docs/plans/task-5-errors.md`

## Delivery and commits (small task, so one part)
1. Commit `docs/retro-notes.md` and this plan (`docs/plans/task-5-errors.md`).
2. Steps 1–3, `./gradlew check`, then test-writer (step 4). **Show its findings and wait.**
3. Commit the feature, the tests and the docs.
4. `/code-review low` on the changed production files only (`JsonInputConfiguration`,
   `ApiExceptionHandler`). **Show its findings before fixing.** Then fix, check, and commit.
5. Push and open the PR, then update sdlc-plan with the PR number and commit and push that.

## Risks
- `getFrom()` may be an instance rather than a `Class` on some Jackson paths. This is handled, and a
  `null` gives `VALIDATION_FAILED`.
- Disabling `ALLOW_COERCION_OF_SCALARS` is global to the HTTP mapper. Today the only scalar target is
  `amount`, and future integer or boolean fields would also reject strings, which is the intent.

## Verification
- `./gradlew test --tests 'com.danielrak.banking.TransactionApiIT' --tests 'com.danielrak.banking.AccountApiIT'`:
  the five formerly disabled tests pass, and the skip count drops to 0.
- `./gradlew check` is green with the coverage gate.
- Manual: `docker compose up -d` + `./gradlew bootRun`, then `curl` a string amount, a duplicate key
  and `"description": 42` against a real account, and confirm each 400 body. Also check that
  `/v3/api-docs` returns 200 and lists both controllers, since springdoc uses the same JSON mapper and
  the coercion change is global.

## Outcome
- **Built:** steps 1–5 as planned. Jackson 3.1.5 details: `JacksonException.Reference.from()` (not
  `getFrom()`). A record creator reports its `Class`, and a list reports its instance.
- **Tests:** the five `@Disabled` tests are enabled, and the duplicate-key one was edited by hand
  (decision 1). test-writer added 11 rule 4 tests (13 cases): 7 in `TransactionApiIT`, 4 in
  `AccountApiIT`. It found no spec-vs-code mismatches. Its ambiguities needed no change:
  - "no `errors[]`" means the property is absent;
  - `""` and `{}` amounts get `INVALID_AMOUNT` either way;
  - with several unreadable values, which one is reported depends on document order, and the
    spec leaves it unspecified.
- **`./gradlew check`:** 189 tests, 0 failed, 0 skipped. Coverage: lines 0.95, branches 0.868.
- **Manual check (compose + bootRun):**
  - `"amount": "10.50"` → 400 `INVALID_AMOUNT` with `errors[amount]`;
  - a duplicate `amount` key → 400 `VALIDATION_FAILED` with no `errors[]`;
  - `"description": 42` → 400 `DESCRIPTION_MISSING`;
  - nothing was posted;
  - `/v3/api-docs` → 200, with all three paths and the Accounts and Transactions tags.
- **Review:** `/code-review low` on `JsonInputConfiguration` and `ApiExceptionHandler` found no
  correctness bugs.
