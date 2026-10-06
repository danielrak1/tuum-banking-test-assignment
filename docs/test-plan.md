# Test plan

This plan traces every requirement of the assignment PDF, and every success criterion in
[`intent.md`](../intent.md), to the tests that prove it. The behaviour under test is specified in
[`design.md`](design.md) §2 (API), §3 (errors), §5 (events) and §6 (the hard criteria). This document only
says which test proves what.

In the tables below:
- `Account`, `Tx`, `Protocol`, `Concurrency` and `Outbox` stand for `AccountApiIT`, `TransactionApiIT`,
  `ProtocolErrorsIT`, `BalanceConcurrencyIT` and `OutboxPublisherIT`;
- `C01`…`C20` are cases of `scripts/contract-check.sh`;
- `[…]` names a case of a parameterized test.

## 1. Test levels

| Level | What it proves | Where | Speed | Runs |
|---|---|---|---|---|
| **Unit** | Logic no HTTP request can reach | `ApiExceptionHandlerTest` (500 paths, type mismatches), `EventJsonTest` (the §5 bytes), `BackoffTest`, `FailureLogTest` | ms | `./gradlew check` |
| **Integration** | The API, DB and broker together: every §2/§3/§5 rule, black-box over HTTP, with events read from `banking.events.all` | `*IT` on Testcontainers (Postgres 18, `rabbitmq:4-management`), one shared Spring context | ~2 min in total | `./gradlew check` |
| **Convention** | Rules that would otherwise have to be remembered | `NotFoundCodeConventionTest` (every UUID path handler has `@NotFoundCode`; path variable before body) | ms | `./gradlew check` |
| **Contract** | The packaged app as a reviewer runs it: image, compose, env config, Flyway on an empty DB, then every PDF request and error, plus events | `scripts/contract-check.sh` against `docker compose up --build --wait` | ~20 s, plus the image build | `verify`, and CI (Stage 5) |
| **Load** | Throughput (TPS, p95) | k6, part B | minutes | by hand, for the README |
| **Manual** | What automation doesn't cover: a real broker outage, a restart that keeps messages, the Swagger UI | the task plans' "Manual check" sections | – | per task |

Most tests are integration tests on purpose. The risk in this service is where the parts meet:
validation order, transaction boundaries, the outbox and the broker. All of that needs real Postgres
and RabbitMQ (CLAUDE.md: never H2, never a mocked broker). Unit tests cover only pure logic that would
be slow or impossible to reach over HTTP.

## 2. PDF errors

Every error the PDF names, with its design.md §3 rows. "Nothing published" means a test proves the
rejected request wrote no event and no row.

| PDF error | §3 status · code | Integration tests | Contract |
|---|---|---|---|
| **Create account: Invalid currency** | 400 `INVALID_CURRENCY` (unsupported, lowercase or null element) | Account.`rejectsUnsupportedOrNullCurrencyElementWithInvalidCurrency` [JPY, eur, null, among valid], `rejectsNonStringCurrencyElementWithInvalidCurrencyAtItsIndex`, `reportsAllFailingFieldsWithCurrencyFirstAndFullProblemShape`, `reportsInvalidCurrencyFirstWhenCurrencyAndCountryFail`; nothing published | C02 |
| **Get account: Account not found** | 404 / 400 `ACCOUNT_NOT_FOUND` (unknown / malformed ID) | Account.`rejectsUnknownAccountIdWith404AccountNotFound`, `rejectsMalformedAccountIdWith400AccountNotFound` [7 shapes], `rejectsBraceWrappedAccountIdWith400AccountNotFound` | C04, C05 |
| **Create transaction: Invalid currency** | 400 `INVALID_CURRENCY` (missing, null, unsupported, lowercase); 422 `INVALID_CURRENCY` (supported but not held) | Tx.`rejectsInvalidFieldWith400AndFieldCode` [currency ×4], `rejectsNumericCurrencyWithInvalidCurrency`, `reportsAllFourFailingFieldsWithInvalidCurrencyOnTop`, `rejectsSupportedCurrencyNotHeldWith422InvalidCurrency`, `checksCurrencyHeldBeforeInsufficientFunds`; nothing published | C08, C09 |
| **Create transaction: Invalid direction** | 400 `INVALID_DIRECTION` | Tx.`rejectsInvalidFieldWith400AndFieldCode` [direction ×4], `rejectsBooleanDirectionWithInvalidDirection`, `reportsInvalidDirectionOnTopWhenCurrencyIsValid` | C10 |
| **Create transaction: Invalid amount** (PDF: negative) | 400 `INVALID_AMOUNT`: negative, zero (§7), > 2 decimals, > 17 integer digits, missing, a JSON string or other non-number | Tx.`rejectsInvalidFieldWith400AndFieldCode` [amount ×12: -1, 0, 0.00, 0.000, 0.001, 10.555, 1e18, 18 digits, huge exponents, missing, null], `rejectsStringAmountWithInvalidAmount`, `rejectsUnparseableAmountWithInvalidAmount`, `rejectsNonNumberAmountWithInvalidAmount` [true, {}, ""], `validatesBodyBeforeAccountExistence`, `reportsInvalidAmountOnTopWhenOnlyAmountAndDescriptionFail`; nothing published | C11 |
| **Create transaction: Insufficient funds** | 422 `INSUFFICIENT_FUNDS` | Tx.`rejectsOutOverBalanceWithInsufficientFundsAndLeavesStateUnchanged`, `rejectsOutOnZeroBalanceWithInsufficientFunds`; Concurrency (both tests: exactly ⌊B/a⌋ succeed, the rest 422) | C12 |
| **Create transaction: Account missing** | 404 / 400 `ACCOUNT_MISSING` | Tx.`rejectsUnknownAccountIdWith404AccountMissingAndWritesNoEvent`, `rejectsMalformedAccountIdWith400AccountMissing` [×3], `checksMalformedAccountIdBeforeBodyWithAccountMissing`, `checksAccountExistenceBeforeCurrencyHeld` | C13, C14 |
| **Create transaction: Description missing** | 400 `DESCRIPTION_MISSING` (missing, null, empty, whitespace including Unicode spaces, non-string) | Tx.`rejectsInvalidFieldWith400AndFieldCode` [description ×9], `rejectsNonStringDescriptionWithDescriptionMissing` [42, true] | C15 |
| **Get transactions: Invalid account** | 404 / 400 `INVALID_ACCOUNT` | Tx.`rejectsUnknownAccountIdOnListWith404InvalidAccount`, `rejectsMalformedAccountIdOnListWith400InvalidAccount` [×3] | C18, C19 |

**Errors the PDF doesn't name** (design.md §3) are tested as thoroughly:
- **`VALIDATION_FAILED`:** country, the `currencies` list, `customerId`, too-long or control-character
  free text, malformed JSON, duplicate keys, a body that isn't an object. Tested by
  Account.`rejectsInvalidFieldWithValidationFailed` (26 cases), `rejectsMalformedJsonWithValidationFailed`,
  and Tx's `VALIDATION_FAILED` cases.
- **Rule order and priority (§3 rules 1–3):** `validatesBodyBeforeAccountExistence`, the
  `checks…Before…` tests and the `reports…OnTop…` tests.
- **Protocol errors (404/405/406/415):** `ProtocolErrorsIT`. A 406 or 415 never posts anything.
- **500:** `ApiExceptionHandlerTest`.

## 3. PDF behaviours

| PDF requirement | Integration tests | Contract |
|---|---|---|
| Create account returns the account ID, the customer ID and balances (available amount, currency) | Account.`createsAccountWithLocationAndZeroBalancesOrderedByCurrency`, `createsAccountWithSingleCurrency` | C01 |
| "API must create balances … in the given currencies" | the same, plus `getsAccountWithZeroBalancesInCurrencyOrderMatchingCreateResponse` | C01, C03 |
| Get account returns the account with its balances | Account.`getsAccountWithZeroBalancesInCurrencyOrderMatchingCreateResponse`, `getsAccountByUppercasedAccountId` | C03 |
| Create transaction returns all seven output fields, including the balance after | Tx.`postsInThenOutAndReturnsTransactionWithRunningBalanceAfter`, the `accepts…` amount tests (scale 2) | C06, C07 |
| IN adds to and OUT subtracts from the balance in that currency | Tx.`postsInThenOut…`, `acceptsOutOfExactlyTheBalanceLeavingZero`, `listsTransactionsInPostOrderWithRunningBalancePerCurrency` | C06, C07, C16 |
| Get transactions returns the list with the PDF fields | Tx.`listsTransactionsInPostOrderWithRunningBalancePerCurrency`, `listsNoTransactionsForNewAccountAsEmptyArray`, `listsOnlyTheRequestedAccountsTransactions` | C17 |
| "Publish all insert and update operations to RabbitMQ" | Account.`publishesAccountCreatedThenOneBalanceCreatedPerCurrency`, Tx.`publishesTransactionCreatedThenBalanceUpdatedPerTransaction` (exact types, order, envelope and data); GETs publish nothing (`getAccountPublishesNoEvent`, `listingTransactionsPublishesNoEvent`) | C20 |
| Executable with Docker; compose includes the DB and RabbitMQ, and initialises the schema | `BankingApplicationTests.contextLoadsAndSchemaIsMigrated` (Flyway in the test context) | the `verify` stack (`up --wait` on empty volumes), C01–C20 |
| Swagger UI (`intent.md`) | `OpenApiIT` (operation IDs, required fields, enums, max lengths, the UI page) | – |

## 4. Success criteria (`intent.md`)

| # | Criterion | Proof |
|---|---|---|
| 1 | Every API behaviour and error has a passing automated test | §2 and §3 above: every row has integration tests, and the PDF's rows also have contract cases. |
| 2 | Coverage ≥ 80%, the build fails below it | JaCoCo gate in `build.gradle`: lines and branches ≥ 0.80, wired into `check`, with only `BankingApplication` (main) excluded. |
| 3 | Balances never go negative under concurrency | `BalanceConcurrencyIT.concurrentOutsSucceedExactlyFloorOfBalanceOverAmountAndNeverGoNegative` (50 × OUT 7.00 on 100.00: exactly 14 succeed, final 2.00) and `concurrentMixedInsAndOutsReconcileAsConsistentRunningBalance` (the ledger reconciles; `balance.updated` arrives in `seq` order). |
| 4 | Events are never lost | `EventDeliveryIT` (write while the broker is paused; the events arrive after it is back). Plus Outbox.`keepsRowsPendingThroughAConfirmTimeout…` (rows stay until confirmed), `neverSendsALaterRowWhileAnEarlierOneIsNacked` (a real nack: no later row overtakes it) and `publishesNothingWhileAnotherInstanceHoldsTheLock…` (two instances). |
| 5 | A fresh clone plus `docker compose up --build` works | `verify`: an isolated stack from empty volumes becomes healthy and passes C01–C20. CI runs the same in Stage 5. |
| 6 | The README covers build and run, choices, TPS, scaling and AI | Not a test. Stage 6, reviewed against the PDF by `spec-checker`. |

## 5. Contract cases (`scripts/contract-check.sh`)

| Case | Request → expected |
|---|---|
| C01 | Create account EUR + USD → 201, ID, customer ID, balances 0.00 |
| C02 | Create account JPY → 400 `INVALID_CURRENCY` |
| C03 | Get account → 200 with ID, customer ID and balances |
| C04 / C05 | Get account, unknown / malformed → 404 / 400 `ACCOUNT_NOT_FOUND` |
| C06 / C07 | IN 100.00 → 201, all output fields, balance after 100.00; OUT 30.50 → balance after 69.50 |
| C08 / C09 | JPY → 400, GBP not held → 422, both `INVALID_CURRENCY` |
| C10 / C11 | Bad direction → 400 `INVALID_DIRECTION`; −5.00 → 400 `INVALID_AMOUNT` |
| C12 | OUT 1000.00 → 422 `INSUFFICIENT_FUNDS` |
| C13 / C14 | Unknown / malformed account → 404 / 400 `ACCOUNT_MISSING` |
| C15 | No description → 400 `DESCRIPTION_MISSING` |
| C16 | Get account → EUR 69.50, USD 0.00, so the rejected requests changed nothing |
| C17 | List → the two transactions in post order, with the PDF fields |
| C18 / C19 | List, unknown / malformed → 404 / 400 `INVALID_ACCOUNT` |
| C20 | With a management URL: the account's 7 events in order (`account.created`, `balance.created` ×2, then `transaction.created` + `balance.updated` ×2). Without one, it is reported as skipped. |

Amounts are compared as written (`69.50`, not `69.5`), so scale 2 is checked too (ADR-0001). The
script exits 1 on any failed case, and 2 if the stack or a tool is missing.

## 6. Coverage targets
- **Gate:** lines and branches ≥ 0.80. `./gradlew check` fails below that.
- **Today:** lines 0.96, branches 0.89.
- **Excluded:** `BankingApplication`, which only holds `main()`.
- **Not chased:** the uncovered branches are mostly defensive (a 0-row `applyDelta` for an `IN`, an
  interrupt during shutdown). A test that only reaches them doesn't add confidence.

## 7. Known gaps (accepted)
- **"Nothing published" isn't asserted by every rejection test.** For example, the string-amount and
  malformed-path-ID tests don't. Every PDF error has at least one test that asserts it (§2), and
  a rejected request either fails validation before the service runs or rolls back its transaction,
  so the remaining tests would add little.
- **Some helpers are still duplicated:** `assertMoney`, `assertTransaction` and
  `assertTransactionEvents` in `TransactionApiIT` and `EventDeliveryIT`. The shared request and problem
  helpers are in `BankingApi` and `BankingEvents`.
- **406 body:** design.md §3 gives other 4xx the code `BAD_REQUEST`. `ProtocolErrorsIT` accepts a 406 with
  or without a problem body, because Spring may not be able to write one in a media type the client
  accepts.
- **A real broker outage** (stop, not pause) and **a restart that keeps queued messages** are manual
  checks (task 4 plan).
- **Balance overflow** past `NUMERIC(19,2)` has no test yet. It is a known limitation, deferred to
  part B (design.md §8).

## 8. How to run
```sh
./gradlew test --tests 'com.danielrak.banking.TransactionApiIT'   # one class (Docker must be running)
./gradlew check                                                     # every test plus the coverage gate
scripts/contract-check.sh [BASE_URL] [RABBITMQ_MGMT_URL]            # against a running stack
.claude/skills/verify/verify.sh                                     # tests (always re-run) + gate, a clean stack + contract check, teardown
```
